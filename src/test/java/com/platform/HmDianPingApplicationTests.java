package com.platform;

import com.platform.entity.Shop;
import com.platform.service.impl.ShopServiceImpl;
import com.platform.utils.CacheClient;
import com.platform.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.platform.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.platform.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
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

    /**
     * 预热店铺数据，按照typeId进行分组，用于实现附近商户搜索功能
     */
    @Test
    public void loadShopListToCache() {
        // 1、获取店铺数据
        List<Shop> shopList = shopService.list();
        // 2、根据 typeId 进行分类
//        Map<Long, List<Shop>> shopMap = new HashMap<>();
//        for (Shop shop : shopList) {
//            Long shopId = shop.getId();
//            if (shopMap.containsKey(shopId)){
//                // 已存在，添加到已有的集合中
//                shopMap.get(shopId).add(shop);
//            }else{
//                // 不存在，直接添加
//                shopMap.put(shopId, Arrays.asList(shop));
//            }
//        }
        // 使用 Lambda 表达式，更加优雅（优雅永不过时）
        Map<Long, List<Shop>> shopMap = shopList.stream()
                .collect(Collectors.groupingBy(Shop::getTypeId));

        // 3、将分好类的店铺数据写入redis
        for (Map.Entry<Long, List<Shop>> shopMapEntry : shopMap.entrySet()) {
            // 3.1 获取 typeId
            Long typeId = shopMapEntry.getKey();
            List<Shop> values = shopMapEntry.getValue();
            // 3.2 将同类型的店铺的写入同一个GEO ( GEOADD key 经度 维度 member )
            String key = SHOP_GEO_KEY + typeId;
            // 方式一：单个写入(这种方式，一个请求一个请求的发送，十分耗费资源，我们可以进行批量操作)
//            for (Shop shop : values) {
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()),
//                shop.getId().toString());
//            }
            // 方式二：批量写入
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            for (Shop shop : values) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

}
