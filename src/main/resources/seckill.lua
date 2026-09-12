-- ============================================================
-- 预约资格抢购脚本（原子操作）
-- 作用：在一次 Lua 执行中完成「库存校验 → 用户去重 → 扣减库存 → 投递订单消息」
-- 返回值：0 = 抢购成功；1 = 库存不足；2 = 重复预约
-- ============================================================

-- 1. 参数列表（由 Java 侧通过 ARGV 传入）
-- 1.1. 预约资格 id（对应 tb_voucher.id，即某个体检课/器械时段）
local voucherId = ARGV[1]
-- 1.2. 用户 id
local userId = ARGV[2]
-- 1.3. 订单 id（由 RedisIdWorker 生成的全局唯一号）
local orderId = ARGV[3]

-- 2. 数据 key
-- 2.1. 库存 key：seckill:stock:{voucherId}
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2. 已预约用户集合 key：seckill:order:{voucherId}（Set 结构，用于一人一单去重）
local orderKey = 'seckill:order:' .. voucherId

-- 3. 脚本业务
-- 3.1. 校验库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回 1
    return 1
end

-- 3.2. 校验该用户是否已经预约过（SISMEMBER）
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 已存在，属于重复下单，返回 2
    return 2
end

-- 3.3. 扣减库存
redis.call('incrby', stockKey, -1)

-- 3.4. 记录该用户已下单（用于后续一人一单校验）
redis.call('sadd', orderKey, userId)

-- 3.5. 投递消息到 Stream，由后台消费者异步落库
--      XADD stream.orders * userId xxx voucherId xxx id xxx
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

-- 3.6. 抢购成功，返回 0
return 0
