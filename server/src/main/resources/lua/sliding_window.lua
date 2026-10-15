-- 滑动窗口限流：ZSet 存请求时间戳，一次 Lua 完成"清理 + 统计 + 写入"
-- KEYS[1] 窗口 key（如 rate:seckill:USER:8）
-- ARGV[1] 当前时间戳（毫秒） ARGV[2] 窗口大小（秒） ARGV[3] 最大请求数 ARGV[4] 本次请求的唯一标识
-- 返回 1 = 放行，0 = 被限流

local key = KEYS[1]
local now = tonumber(ARGV[1])
local windowMillis = tonumber(ARGV[2]) * 1000
local maxRequests = tonumber(ARGV[3])
local member = ARGV[4]

-- 1) 移除窗口外的历史请求（这就是"滑动"：窗口跟着当前时间往前走）
redis.call('ZREMRANGEBYSCORE', key, 0, now - windowMillis)

-- 2) 统计窗口内的请求数
local count = redis.call('ZCARD', key)
if count >= maxRequests then
    return 0
end

-- 3) 记录本次请求，并把 key 的过期时间设为窗口大小（没人访问时自动回收）
redis.call('ZADD', key, now, member)
redis.call('PEXPIRE', key, windowMillis)
return 1
