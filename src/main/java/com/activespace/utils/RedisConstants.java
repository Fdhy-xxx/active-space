package com.activespace.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    /** 场馆缓存的逻辑过期时间（秒）。查询链路与预热任务共用，避免两处配置不一致 */
    public static final Long CACHE_SHOP_LOGICAL_EXPIRE = 20L;

    /** 缓存预热任务的分布式锁 key */
    public static final String LOCK_CACHE_WARMUP_KEY = "lock:cache:warmup:shop";

    public static final String FOLLOW_KEY = "follows:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";

    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
