package com.activespace.utils;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.activespace.utils.RedisConstants.CACHE_NULL_TTL;
import static com.activespace.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * <p>
 * 缓存工具类
 * </p>
 * <p>
 * 提供三种查询策略：
 * 1. queryWithPassThrough —— 空值缓存解决缓存穿透；
 * 2. queryWithLogicalExpire —— 逻辑过期 + 异步重建解决缓存击穿（当前场馆查询使用）；
 * 3. queryWithMutex —— 互斥锁 + 双重检查解决缓存击穿（强一致性场景使用）。
 * <p>
 * 缓存重建统一使用 Redisson 分布式锁：锁的获取与释放都在同一个线程内完成。
 * 注意：Redisson 的锁与线程绑定（可重入语义），不能在 A 线程加锁、B 线程解锁。
 *
 * @author 郑新跃
 */
@Slf4j
@Component
public class CacheClient {

    /** 同步互斥锁回源时的最大等待时间（秒） */
    private static final long MUTEX_LOCK_WAIT_SECONDS = 3L;

    /** 异步重建时的等待时间（秒）：不等待，拿不到说明已有线程在重建 */
    private static final long REBUILD_LOCK_WAIT_SECONDS = 0L;

    /** 锁的持有时长（秒），缓存重建耗时远小于该值 */
    private static final long LOCK_LEASE_SECONDS = 10L;

    /** 物理过期时间的随机抖动比例（百分比），用于避免批量 Key 同时失效引发缓存雪崩 */
    private static final int TTL_JITTER_PERCENT = 10;

    /** 缓存重建线程池 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    private final RedissonClient redissonClient;

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    /**
     * 写入带物理过期时间的缓存，TTL 附加 10% 以内的随机抖动
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        long baseSeconds = unit.toSeconds(time);
        long jitter = RandomUtil.randomLong(0, Math.max(1L, baseSeconds * TTL_JITTER_PERCENT / 100));
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(value), baseSeconds + jitter, TimeUnit.SECONDS);
    }

    /**
     * 写入逻辑过期结构的缓存（不设置物理过期时间，从根本上避免批量失效）
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 空值缓存方案解决缓存穿透
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 命中的是空值，说明数据不存在，直接返回
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            // 数据库中也不存在，写入空值并设置较短 TTL
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 逻辑过期方案解决缓存击穿：不阻塞请求，返回旧数据的同时异步重建缓存
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 缓存中不存在（可能是被空值缓存标记为不存在），直接返回
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 逻辑过期时间未到，直接返回
            return r;
        }

        // 已逻辑过期：异步重建，当前请求仍返回旧数据，保证可用性
        String lockKey = LOCK_SHOP_KEY + id;
        CACHE_REBUILD_EXECUTOR.submit(
                () -> rebuildWithLogicalExpire(key, lockKey, id, dbFallback, time, unit));
        return r;
    }

    /**
     * 异步重建缓存
     * 锁在重建线程内获取、也在重建线程内释放，避免跨线程解锁；
     * 拿到锁后再次检查缓存，避免等锁期间已被其他线程重建。
     */
    private <R, ID> void rebuildWithLogicalExpire(
            String key, String lockKey, ID id, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(REBUILD_LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!isLock) {
                // 已有线程在重建，直接返回
                return;
            }
            // 双重检查：等锁期间可能已被其他线程重建
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return;
            }
            R newR = dbFallback.apply(id);
            if (newR == null) {
                // 数据已不存在，写入空值缓存，避免持续回源
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return;
            }
            this.setWithLogicalExpire(key, newR, time, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("缓存重建获取锁被中断, key={}", key);
        } catch (Exception e) {
            log.error("缓存重建失败, key={}", key, e);
        } finally {
            if (isLock && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 互斥锁方案解决缓存击穿：保证同一时刻只有一个线程回源，其余线程等待后重读缓存
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLock = false;
        try {
            // 最多等待 MUTEX_LOCK_WAIT_SECONDS 秒；替代原先的递归重试，避免递归深度不可控
            isLock = lock.tryLock(MUTEX_LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!isLock) {
                log.warn("获取缓存重建锁超时, key={}", key);
                return null;
            }
            // 双重检查：等锁期间可能已被其他线程重建
            String again = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(again)) {
                return JSONUtil.toBean(again, type);
            }
            R r = dbFallback.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key, r, time, unit);
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            if (isLock && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
