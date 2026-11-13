package com.zjl.worklog.auth;

import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.ticket.TicketRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 登录入口的 IP 速率限制与账号/IP 失败锁定 */
class LoginGuardTest {

    private LoginGuard guard(int ipMax, int userFailMax, int ipFailMax) {
        return new LoginGuard(new TicketRateLimiter(),
                new LoginGuard.Limits(ipMax, 60, userFailMax, ipFailMax, 900));
    }

    @Test
    @DisplayName("同一 IP 在窗口内超过次数后拒绝，换 IP 不受影响")
    void ipRateLimitIsPerAddress() {
        LoginGuard guard = guard(2, 5, 30);
        guard.checkAttempt("alice", "10.0.0.1");
        guard.checkAttempt("bob", "10.0.0.1");

        BizException blocked = assertThrows(BizException.class,
                () -> guard.checkAttempt("carol", "10.0.0.1"));
        assertEquals(42901, blocked.getCode());

        guard.checkAttempt("carol", "10.0.0.2");
    }

    @Test
    @DisplayName("同一账号连续失败达到阈值后锁定，大小写与首尾空格算同一个账号")
    void userFailuresLockThatAccountOnly() {
        LoginGuard guard = guard(100, 2, 30);
        guard.checkAttempt("Alice", "10.0.0.1");
        guard.onFailure("Alice", "10.0.0.1");
        guard.checkAttempt(" alice ", "10.0.0.2");
        guard.onFailure(" alice ", "10.0.0.2");

        BizException blocked = assertThrows(BizException.class,
                () -> guard.checkAttempt("ALICE", "10.9.9.9"));
        assertEquals(42905, blocked.getCode());

        guard.checkAttempt("bob", "10.9.9.9");
    }

    @Test
    @DisplayName("同一 IP 的失败次数跨账号累计，锁的是这个 IP")
    void ipFailuresLockThatAddressOnly() {
        LoginGuard guard = guard(100, 5, 2);
        guard.checkAttempt("alice", "10.0.0.8");
        guard.onFailure("alice", "10.0.0.8");
        guard.checkAttempt("bob", "10.0.0.8");
        guard.onFailure("bob", "10.0.0.8");

        BizException blocked = assertThrows(BizException.class,
                () -> guard.checkAttempt("carol", "10.0.0.8"));
        assertEquals(42905, blocked.getCode());

        guard.checkAttempt("carol", "10.0.0.9");
    }

    @Test
    @DisplayName("登录成功后清掉该账号的失败计数，IP 失败计数保留")
    void successClearsUserFailuresButNotIpFailures() {
        LoginGuard guard = guard(100, 2, 5);
        guard.checkAttempt("alice", "10.0.0.3");
        guard.onFailure("alice", "10.0.0.3");
        guard.onSuccess("alice", "10.0.0.3");

        guard.checkAttempt("alice", "10.0.0.4");
        guard.onFailure("alice", "10.0.0.4");
        guard.checkAttempt("alice", "10.0.0.4");

        LoginGuard ipGuard = guard(100, 100, 2);
        ipGuard.checkAttempt("alice", "10.8.8.8");
        ipGuard.onFailure("alice", "10.8.8.8");
        ipGuard.onSuccess("alice", "10.8.8.8");
        ipGuard.checkAttempt("bob", "10.8.8.8");
        ipGuard.onFailure("bob", "10.8.8.8");
        BizException blocked = assertThrows(BizException.class,
                () -> ipGuard.checkAttempt("carol", "10.8.8.8"));
        assertEquals(42905, blocked.getCode());
    }
}
