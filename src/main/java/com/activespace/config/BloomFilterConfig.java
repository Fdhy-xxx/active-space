package com.activespace.config;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <p>
 * 布隆过滤器配置
 * </p>
 * <p>
 * 用于在查询链路最前置拦截「一定不存在」的场馆 id，避免无效请求穿透到缓存与数据库。
 * 说明：
 * 1. 选用 Redisson 的 RBloomFilter 而不是 Guava BloomFilter —— 前者基于 Redis 位操作实现，
 * 多实例共享同一份数据、应用重启不丢失，符合分布式场景；
 * 2. RBloomFilter 只做「一定不存在」的判断，不会出现假阴性（存在的元素一定判定为存在），
 * 因此不会误伤真实数据，只存在一定概率的假阳性（不存在的元素被判为存在）；
 * 3. 独立指定 LongCodec，避免依赖 Redisson 默认编解码器对基本类型的序列化行为。
 * </p>
 *
 * @author 郑新跃
 */
@Configuration
public class BloomFilterConfig {

    /** 布隆过滤器在 Redis 中的 key */
    public static final String SHOP_BLOOM_KEY = "bloom:shop";

    /** 预估元素数量：按未来一年的场馆总量估算 */
    private static final long EXPECTED_INSERTIONS = 100_000L;

    /** 可接受的误判率 */
    private static final double FALSE_PROBABILITY = 0.01D;

    @Bean
    public RBloomFilter<Long> shopBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<Long> bloomFilter =
                redissonClient.getBloomFilter(SHOP_BLOOM_KEY, new LongCodec());
        // tryInit 具备幂等性：key 已存在时不会重复初始化
        bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
        return bloomFilter;
    }
}
