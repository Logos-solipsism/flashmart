-- 回滚库存：只在"当前库存 < 活动总库存"时才 +1
-- 为什么要有上限：超时关单的消息可能来得比一次压测重置还晚，
-- 无脑 INCR 会让 Redis 库存超过活动总量（实测出现过 9999 → 11679）。
-- 数据库那边有 available_stock < total_stock 的条件保护，Redis 侧也要一致。
-- KEYS[1] 库存 key   KEYS[2] 总库存 key（不存在时不做上限判断）
-- 返回：回滚后的库存；库存 key 不存在时返回 -1

local stock = redis.call('GET', KEYS[1])
if stock == nil then
    return -1
end
stock = tonumber(stock)

local total = redis.call('GET', KEYS[2])
if total ~= nil and stock >= tonumber(total) then
    return stock
end
return redis.call('INCR', KEYS[1])
