package com.activespace.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.activespace.dto.Result;
import com.activespace.entity.VoucherOrder;
import com.activespace.mapper.VoucherOrderMapper;
import com.activespace.service.ISeckillVoucherService;
import com.activespace.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.activespace.utils.RedisIdWorker;
import com.activespace.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 郑新跃
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /** 下单锁 key 前缀（按用户维度加锁，用于防止同一用户并发重复下单） */
    private static final String LOCK_ORDER_KEY = "lock:order:";

    /** 下单锁最大等待时间（秒） */
    private static final long ORDER_LOCK_WAIT_SECONDS = 3L;

    /** pending-list 最大重试次数，防止异常消息导致无限重试 */
    private static final int MAX_PENDING_RETRY = 5;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //5.在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    // 用于线程池处理的任务
    // 6.当初始化完毕后，就会去从阻塞对列中去拿信息
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        // 增加重试上限：消息未 ACK 时 pending-list 不会清空，原 while(true) 存在无限重试风险
        int attempt = 0;
        while (attempt < MAX_PENDING_RETRY) {
            try {
                // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1  STREAMS s1 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    // 如果为null，说明pending-list没有异常消息，结束循环
                    break;
                }
                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                // 3.创建订单
                handleVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("stream.orders","g1",record.getId());
            } catch (Exception e) {
                attempt++;
                log.error("处理pending-list订单中的异常，已重试 {} 次", attempt, e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (attempt >= MAX_PENDING_RETRY) {
            log.error("pending-list 重试已达上限 {} 次，仍有消息未确认，请人工检查 stream.orders",
                    MAX_PENDING_RETRY);
        }
    }

    /*//设置阻塞队列的内存大小为1024*1024
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 用于线程池处理的任务
    // 6.当初始化完毕后，就会去从阻塞对列中去拿信息
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/


    //7.从阻塞对列中去拿订单信息
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象（按用户粒度加锁，防止同一用户并发重复下单）
        RLock redisLock = redissonClient.getLock(LOCK_ORDER_KEY + userId);
        // 3.尝试获取锁：最多等待 ORDER_LOCK_WAIT_SECONDS 秒
        boolean isLock;
        try {
            isLock = redisLock.tryLock(ORDER_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取下单锁被中断, userId=" + userId, e);
        }
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 【重要】这里必须抛出异常，不能直接 return：
            // 直接 return 会让消费者正常返回、消息被 ACK，导致这一单被静默丢弃（Redis 库存已扣、订单未生成）；
            // 抛出异常则不会执行 acknowledge，消息保留在 pending-list 中由 handlePendingList() 重试。
            throw new RuntimeException("获取下单锁失败，等待重试, userId=" + userId);
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁：仅在当前线程持有锁时释放，避免误删其他线程的锁
            if (redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }


    private IVoucherOrderService proxy;

    /**
     * 优惠券秒杀下单
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId));
        // 2.判断结果是否为0
        if (result != 0L) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(result == 1L ? "库存不足" : "不能重复下单");
        }
        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();

        //创建锁对象(新增代码)
        //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if(!isLock){
            return Result.fail("一个人只允许下一单!!");
        }
        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }*/

    //8.实现异步下单功能
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        Long userId = voucherOrder.getUserId();
        synchronized (userId.toString().intern()) {
            //5.1查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            //5.2判断是否存在
            if (count > 0) {
                log.error("用户已经购买过了");
                return;
            }
            //6，扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")//set stock = stock - 1
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where id = ? and stock > 0
                    .update();

            if (!success) {
                log.error("库存不足！");
                return;
            }
            save(voucherOrder);
        }
    }
}
