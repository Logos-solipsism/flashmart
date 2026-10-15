-- 抢购核心：把"判断限购 + 判断库存 + 扣库存"合成一次原子执行
-- 为什么必须用 Lua：如果分成三条命令，并发下会出现
--   ① 两个请求都读到库存 1 → 双双扣减 → 超卖；
--   ② 同一用户的两个请求都读到"未购买过" → 落下两单。
-- KEYS[1] 库存 key：seckill:stock:{activityId}
-- KEYS[2] 已购记录 key：seckill:bought:{activityId}  （Hash：userId -> 已购数量）
-- ARGV[1] userId   ARGV[2] 每人限购数量
-- 返回：>=0 扣减成功后的剩余库存；-1 库存不足；-2 超过限购；-3 活动未预热

local stock = redis.call('GET', KEYS[1])
if stock == nil then
    return -3
end
stock = tonumber(stock)
if stock <= 0 then
    return -1
end

local bought = tonumber(redis.call('HGET', KEYS[2], ARGV[1]) or '0')
if bought + 1 > tonumber(ARGV[2]) then
    return -2
end

redis.call('DECR', KEYS[1])
redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
return stock - 1
