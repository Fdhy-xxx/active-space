package com.activespace.task;

import com.activespace.entity.Shop;
import com.activespace.service.IShopService;
import com.activespace.utils.CacheClient;
import com.activespace.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 热点场馆缓存预热任务
 * </p>
 * <p>
 * 设计说明：
 * 1. 当前场馆查询采用「逻辑过期 + 互斥锁」方案，逻辑过期方案的前提是缓存中必须已经存在数据，
 * 否则请求会直接回源到数据库，预热的作用就是把热点数据提前写入缓存；
 * 2. 应用按多实例部署时，每个实例都会触发定时任务，因此使用 Redisson 分布式锁保证
 * 同一时刻只有一个实例执行预热，拿不到锁的实例直接跳过本轮，不做阻塞等待；
 * 3. 任务本身是幂等的：预热只是覆盖写入缓存，重复执行不会产生副作用。
 * </p>
 *
 * @author 郑新跃
 */
@Slf4j
@Component
public class CacheWarmUpTask {

    /** 单次预热的热点场馆数量上限 */
    private static final int TOP_N = 100;

    /** 预热任务持锁的最长时间（秒），防止任务异常时锁无法释放 */
    private static final long LOCK_LEASE_SECONDS = 10L;

    /** 预热任务启动延迟（毫秒），避开应用启动时的资源竞争 */
    private static final long INITIAL_DELAY_MS = 30 * 1000L;

    /** 预热任务执行间隔（毫秒） */
    private static final long FIXED_DELAY_MS = 10 * 60 * 1000L;

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 预热热点场馆缓存
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = FIXED_DELAY_MS)
    public void warmUpShopCache() {
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_CACHE_WARMUP_KEY);
        boolean locked;
        try {
            // 等待 0 秒（拿不到立即返回），锁最长持有 LOCK_LEASE_SECONDS 秒
            locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("【缓存预热】获取分布式锁被中断，跳过本轮执行");
            return;
        }
        if (!locked) {
            log.info("【缓存预热】其他实例正在执行预热，本实例跳过本轮");
            return;
        }

        try {
            long start = System.currentTimeMillis();
            // 说明：表中暂无访问量/预约量字段，暂以 id 倒序取最近录入的 TopN 作为热点数据，
            // 后续如有真实热度统计可替换为按热度排序。
            List<Shop> hotShops = shopService.query()
                    .orderByDesc("id")
                    .last("limit " + TOP_N)
                    .list();

            for (Shop shop : hotShops) {
                cacheClient.setWithLogicalExpire(
                        RedisConstants.CACHE_SHOP_KEY + shop.getId(),
                        shop,
                        RedisConstants.CACHE_SHOP_LOGICAL_EXPIRE,
                        TimeUnit.SECONDS);
            }
            log.info("【缓存预热】执行完成，场馆数={}，耗时={}ms",
                    hotShops.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("【缓存预热】执行异常", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
