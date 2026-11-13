package com.zjl.worklog.auth;

import com.zjl.worklog.auth.dto.LoginRequest;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.JwtTokenService;
import com.zjl.worklog.security.PasswordService;
import com.zjl.worklog.ticket.TicketRateLimiter;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 登录接口必须先过验证码，失败要记账，成功才签发 token */
class AuthLoginProtectionTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final PasswordService passwordService = mock(PasswordService.class);
    private final JwtTokenService tokenService = mock(JwtTokenService.class);
    private final LoginCaptchaService captcha =
            new LoginCaptchaService(Clock.systemUTC(), 120_000L, () -> "ab12");
    private final LoginGuard guard = new LoginGuard(new TicketRateLimiter(),
            new LoginGuard.Limits(30, 60, 5, 30, 900));
    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(userMapper, passwordService, tokenService, guard, captcha);
    }

    @Test
    @DisplayName("验证码错误时不查用户、不计失败锁定")
    void wrongCaptchaDoesNotTouchAccount() {
        LoginRequest req = body("admin", "secret", "nope");

        BizException e = assertThrows(BizException.class, () -> controller.login(req, ip("10.1.1.1")));
        assertEquals(4003, e.getCode());
        verify(userMapper, never()).selectByUsername(anyString());

        // 验证码输错不应把账号推进失败锁定
        for (int i = 0; i < 5; i++) {
            LoginRequest again = body("admin", "secret", "nope");
            assertThrows(BizException.class, () -> controller.login(again, ip("10.1.1.1")));
        }
        BizException stillCaptcha = assertThrows(BizException.class,
                () -> controller.login(body("admin", "secret", "nope"), ip("10.1.1.1")));
        assertEquals(4003, stillCaptcha.getCode());
    }

    @Test
    @DisplayName("口令连续失败 5 次后，第 6 次在查库前被锁定")
    void fifthPasswordFailureLocksTheAccount() {
        when(userMapper.selectByUsername("admin")).thenReturn(user());
        when(passwordService.matchesAndUpgrade(any(), anyString())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            BizException wrong = assertThrows(BizException.class,
                    () -> controller.login(valid("admin", "bad"), ip("10.2.2.2")));
            assertEquals(4001, wrong.getCode());
        }

        BizException locked = assertThrows(BizException.class,
                () -> controller.login(valid("admin", "bad"), ip("10.2.2.3")));
        assertEquals(42905, locked.getCode());
        verify(userMapper, times(5)).selectByUsername("admin");
    }

    @Test
    @DisplayName("验证码和口令都正确时签发 token，并清掉该账号的失败计数")
    void successIssuesTokenAndClearsUserLock() {
        UserEntity user = user();
        when(userMapper.selectByUsername("admin")).thenReturn(user);
        when(passwordService.matchesAndUpgrade(any(), eq("bad"))).thenReturn(false);
        when(passwordService.matchesAndUpgrade(any(), eq("secret"))).thenReturn(true);
        when(tokenService.generateToken(any(), any(), any(), any(), any())).thenReturn("tok");
        when(tokenService.effectiveExpireSeconds()).thenReturn(3600L);

        for (int i = 0; i < 4; i++) {
            assertEquals(4001, assertThrows(BizException.class,
                    () -> controller.login(valid("admin", "bad"), ip("10.3.3.3"))).getCode());
        }

        var resp = controller.login(valid("admin", "secret"), ip("10.3.3.3"));
        assertEquals("tok", resp.getData().getToken());

        // 成功前已经错了 4 次。若成功没清掉计数，再错 2 次就会被锁；清掉之后这两次仍只是口令错误。
        assertEquals(4001, assertThrows(BizException.class,
                () -> controller.login(valid("admin", "bad"), ip("10.3.3.4"))).getCode());
        assertEquals(4001, assertThrows(BizException.class,
                () -> controller.login(valid("admin", "bad"), ip("10.3.3.4"))).getCode());
    }

    private LoginRequest valid(String username, String password) {
        LoginCaptchaService.Issued issued = captcha.issue();
        return body(username, password, "ab12", issued.id());
    }

    private static LoginRequest body(String username, String password, String code) {
        return body(username, password, code, "missing");
    }

    private static LoginRequest body(String username, String password, String code, String captchaId) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        req.setCaptchaCode(code);
        req.setCaptchaId(captchaId);
        return req;
    }

    private static UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setUsername("admin");
        user.setRealName("管理员");
        user.setDeptId(1L);
        user.setStatus(1);
        user.setRole("ADMIN");
        return user;
    }

    private static HttpServletRequest ip(String addr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(addr);
        return request;
    }
}
