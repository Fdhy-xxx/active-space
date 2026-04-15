package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopServiceImpl;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;


    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopServiceImpl.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop,10L, TimeUnit.SECONDS);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        // 1. 初始化CountDownLatch，计数器初始值为300
        CountDownLatch latch = new CountDownLatch(300);

        // 2. 定义任务：每个线程执行100次ID生成
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                // 调用Redis分布式ID生成器，生成order业务的全局唯一ID
                long id = redisIdWorker.nextId("order");
                // 打印生成的ID（可用于验证ID是否唯一、是否有序）
                System.out.println("id = " + id);
            }
            // 任务执行完成，计数器-1
            latch.countDown();
        };

        // 3. 记录任务开始时间
        long begin = System.currentTimeMillis();

        // 4. 提交300个任务到线程池，并发执行
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        // 5. 主线程阻塞，等待所有300个任务执行完成（计数器归0）
        latch.await();

        // 6. 记录任务结束时间，计算总耗时
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }
}
