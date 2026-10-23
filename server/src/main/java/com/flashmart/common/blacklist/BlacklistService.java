package com.flashmart.common.blacklist;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 应用层 IP 黑名单。
 *
 * <p>为什么要有它：限流（滑动窗口）每拒绝一次请求都要跑一次 Redis Lua（清 ZSet + 统计 + 写入）。
 * 被持续攻击时，限流组件自己反而成了 Redis 的热点。黑名单把「已经确认在刷的 IP」提前挡在
 * Servlet 过滤器层——一个 GET，连 Lua 都不跑。
 *
 * <p>计数口径：限流拒绝一次算一次违规（{@link #recordViolation}），
 * 窗口内违规数达到阈值就封 ban-seconds 秒。这几个数字都是<b>策略值</b>，不是实测最优值，
 * 生产要按真实流量调（见 application.yml 的 flashmart.blacklist 段）。
 *
 * <p>两条铁律：
 * <ul>
 *   <li><b>fail-open</b>：Redis 异常一律当作「没封 / 不计」，黑名单是保护手段，
 *       不能因为保护组件自己挂了把业务请求全拒掉；</li>
 *   <li><b>绝不上抛</b>：写违规计数失败也只是 log.warn，不能让限流路径抛异常。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlacklistService {

    private static final String BAN_KEY_PREFIX = "black:list:";
    private static final String VIOLATION_KEY_PREFIX = "black:violation:";

    private final StringRedisTemplate redis;

    /** 总开关：关掉后过滤器直接放行，读 Redis 的动作也省掉 */
    @Value("${flashmart.blacklist.enabled:true}")
    private boolean enabled;

    /** 窗口内违规达到这个次数就封禁 */
    @Value("${flashmart.blacklist.violation-threshold:20}")
    private int violationThreshold;

    /** 违规计数的统计窗口（秒） */
    @Value("${flashmart.blacklist.violation-window-seconds:60}")
    private long violationWindowSeconds;

    /** 封禁时长（秒） */
    @Value("${flashmart.blacklist.ban-seconds:300}")
    private long banSeconds;

    /** 被黑名单拒掉的请求数：进程内计数，零网络开销，仅供压测取证 */
    private final AtomicLong rejectedCount = new AtomicLong();

    /** 该 IP 当前是否在封禁名单里；ip 无效或 Redis 异常都返回 false（fail-open） */
    public boolean isBanned(String ip) {
        if (!isValidIp(ip)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(banKey(ip)));
        } catch (Exception e) {
            log.warn("黑名单查询失败, 降级放行 ip={}, cause={}", ip, e.getMessage());
            return false;
        }
    }

    /**
     * 记一次违规。ip 无效直接返回（不计数，否则无头请求会挤进同一个桶把别人封了）；
     * 达到阈值就封禁并清掉计数，重新开始统计。
     */
    public void recordViolation(String ip) {
        if (!isValidIp(ip)) {
            return;
        }
        try {
            String violationKey = violationKey(ip);
            Long count = redis.opsForValue().increment(violationKey);
            if (count == null) {
                return;
            }
            if (count == 1L) {
                // 第一次违规才设过期：每次 INCR 都 EXPIRE 会变成固定窗口，永不衰减
                redis.expire(violationKey, Duration.ofSeconds(violationWindowSeconds));
            }
            if (count >= violationThreshold) {
                redis.opsForValue().set(banKey(ip), "1", Duration.ofSeconds(banSeconds));
                redis.delete(violationKey);
                log.warn("IP 违规次数达到阈值已封禁, ip={}, violations={}, banSeconds={}",
                        ip, count, banSeconds);
            }
        } catch (Exception e) {
            // 绝不上抛：限流路径不能因为黑名单写失败而报错
            log.warn("记录黑名单违规失败（忽略）, ip={}, cause={}", ip, e.getMessage());
        }
    }

    /** 删除该 IP 的封禁与违规计数，压测/测试清场用 */
    public void reset(String ip) {
        if (!isValidIp(ip)) {
            return;
        }
        try {
            redis.delete(List.of(banKey(ip), violationKey(ip)));
        } catch (Exception e) {
            log.warn("重置黑名单失败（忽略）, ip={}, cause={}", ip, e.getMessage());
        }
    }

    /** 过滤器每拒掉一个请求就调一次：纯内存自增，不碰 Redis */
    public void recordRejected() {
        rejectedCount.incrementAndGet();
    }

    public long rejectedCount() {
        return rejectedCount.get();
    }

    /** 供压测口子把取证计数清零 */
    public void resetRejectedCount() {
        rejectedCount.set(0);
    }

    /** ip 为空 / 空白 / unknown-ip 一律当作无效，不查也不写 Redis */
    private boolean isValidIp(String ip) {
        return ip != null && !ip.isBlank() && !ClientIpResolver.UNKNOWN_IP.equals(ip);
    }

    private String banKey(String ip) {
        return BAN_KEY_PREFIX + ip;
    }

    private String violationKey(String ip) {
        return VIOLATION_KEY_PREFIX + ip;
    }
}
