package com.zjl.worklog.auth;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.config.SysConfigService;
import com.zjl.worklog.ticket.TicketRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 登录防暴力破解：按 IP 限速，并按账号、IP 两条线记下失败次数。
 *
 * <p>计数复用工单公开接口那套内存滑动窗口。这是单机部署，不另加 Redis。
 * 进程重启后计数清零，窗口本身只有十几分钟，可以接受。
 */
@Component
public class LoginGuard {

    /** 与工单换票接口同一套「太频繁」业务码，前端按文案展示即可 */
    public static final int CODE_RATE_LIMITED = 42901;
    /** 与工单查询密码锁定同一套业务码 */
    public static final int CODE_LOCKED = 42905;

    static final int DEFAULT_IP_COUNT = 30;
    static final long DEFAULT_IP_WINDOW_SECONDS = 60L;
    static final int DEFAULT_FAIL_PER_USER = 5;
    static final int DEFAULT_FAIL_PER_IP = 30;
    static final long DEFAULT_LOCK_WINDOW_SECONDS = 900L;

    private final TicketRateLimiter limiter;
    private final SysConfigService sysConfig;
    private final Limits fixed;

    @Autowired
    public LoginGuard(TicketRateLimiter limiter, SysConfigService sysConfig) {
        this.limiter = limiter;
        this.sysConfig = sysConfig;
        this.fixed = null;
    }

    LoginGuard(TicketRateLimiter limiter, Limits fixed) {
        this.limiter = limiter;
        this.sysConfig = null;
        this.fixed = fixed;
    }

    /**
     * 这次尝试能不能进入口令校验。
     * 先看 IP 速率，再看账号或 IP 是否已经因为失败被锁住。
     */
    public void checkAttempt(String username, String ip) {
        Limits limits = limits();
        String address = addressOf(ip);
        if (!limiter.acquire(rateKey(address), limits.ipMax, limits.ipWindowSeconds)) {
            throw new BizException(CODE_RATE_LIMITED, "操作过于频繁，请稍后再试");
        }
        if (limiter.blocked(userFailKey(username), limits.userFailMax, limits.lockWindowSeconds)
                || limiter.blocked(ipFailKey(address), limits.ipFailMax, limits.lockWindowSeconds)) {
            throw new BizException(CODE_LOCKED, "登录失败次数过多，请稍后再试");
        }
    }

    /** 口令错误、账号不存在或账号被禁用时各记一笔。验证码错误不算。 */
    public void onFailure(String username, String ip) {
        Limits limits = limits();
        limiter.acquire(userFailKey(username), limits.userFailMax, limits.lockWindowSeconds);
        limiter.acquire(ipFailKey(addressOf(ip)), limits.ipFailMax, limits.lockWindowSeconds);
    }

    /** 登录成功后解开这个账号的失败记录。IP 上的失败记录保留，避免换个账号就把出口 IP 洗白。 */
    public void onSuccess(String username, String ip) {
        limiter.forget(userFailKey(username));
    }

    private Limits limits() {
        if (fixed != null) {
            return fixed;
        }
        return new Limits(
                clamp(sysConfig.getInt(SysConfigService.GROUP_RATE_LIMIT,
                        SysConfigService.KEY_LOGIN_IP_COUNT, DEFAULT_IP_COUNT), 1, 1_000),
                clamp(sysConfig.getLong(SysConfigService.GROUP_RATE_LIMIT,
                        SysConfigService.KEY_LOGIN_IP_WINDOW_SECONDS, DEFAULT_IP_WINDOW_SECONDS), 1, 3_600),
                clamp(sysConfig.getInt(SysConfigService.GROUP_RATE_LIMIT,
                        SysConfigService.KEY_LOGIN_FAIL_PER_USER, DEFAULT_FAIL_PER_USER), 1, 100),
                clamp(sysConfig.getInt(SysConfigService.GROUP_RATE_LIMIT,
                        SysConfigService.KEY_LOGIN_FAIL_PER_IP, DEFAULT_FAIL_PER_IP), 1, 10_000),
                clamp(sysConfig.getLong(SysConfigService.GROUP_RATE_LIMIT,
                        SysConfigService.KEY_LOGIN_LOCK_WINDOW_SECONDS, DEFAULT_LOCK_WINDOW_SECONDS), 60, 86_400)
        );
    }

    private static String userFailKey(String username) {
        return "login:fail:user:" + accountOf(username);
    }

    private static String ipFailKey(String address) {
        return "login:fail:ip:" + address;
    }

    private static String rateKey(String address) {
        return "login:ip:" + address;
    }

    static String accountOf(String username) {
        String value = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    static String addressOf(String ip) {
        String value = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    record Limits(int ipMax, long ipWindowSeconds, int userFailMax, int ipFailMax, long lockWindowSeconds) {
    }
}
