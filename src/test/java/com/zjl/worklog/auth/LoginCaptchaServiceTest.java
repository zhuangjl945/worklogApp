package com.zjl.worklog.auth;

import com.zjl.worklog.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 登录验证码：一次性、过期失效、大小写不敏感 */
class LoginCaptchaServiceTest {

    @Test
    @DisplayName("签发的验证码忽略大小写和首尾空格，用过一次即作废")
    void codeIsCaseInsensitiveAndSingleUse() {
        LoginCaptchaService service = new LoginCaptchaService(Clock.systemUTC(), 120_000L, () -> "ab12");
        LoginCaptchaService.Issued issued = service.issue();

        assertTrue(issued.image().startsWith("data:image/png;base64,"));
        service.verifyAndConsume(issued.id(), " AB12 ");

        BizException again = assertThrows(BizException.class,
                () -> service.verifyAndConsume(issued.id(), "ab12"));
        assertEquals(4003, again.getCode());
    }

    @Test
    @DisplayName("验证码错误时作废，不能拿原图再试")
    void wrongCodeConsumesChallenge() {
        LoginCaptchaService service = new LoginCaptchaService(Clock.systemUTC(), 120_000L, () -> "zx81");
        LoginCaptchaService.Issued issued = service.issue();

        BizException wrong = assertThrows(BizException.class,
                () -> service.verifyAndConsume(issued.id(), "nope"));
        assertEquals(4003, wrong.getCode());

        BizException again = assertThrows(BizException.class,
                () -> service.verifyAndConsume(issued.id(), "zx81"));
        assertEquals(4003, again.getCode());
    }

    @Test
    @DisplayName("过期验证码不能用")
    void expiredCodeRejected() {
        MutableClock clock = new MutableClock();
        LoginCaptchaService service = new LoginCaptchaService(clock, 1_000L, () -> "qwer");
        LoginCaptchaService.Issued issued = service.issue();
        clock.plus(1_001L);

        BizException expired = assertThrows(BizException.class,
                () -> service.verifyAndConsume(issued.id(), "qwer"));
        assertEquals(4003, expired.getCode());
    }

    @Test
    @DisplayName("缺少验证码编号时拒绝")
    void missingIdRejected() {
        LoginCaptchaService service = new LoginCaptchaService(Clock.systemUTC(), 120_000L, () -> "abcd");
        BizException missing = assertThrows(BizException.class,
                () -> service.verifyAndConsume("  ", "abcd"));
        assertEquals(4003, missing.getCode());
    }

    private static final class MutableClock extends Clock {
        private long millis = 1_700_000_000_000L;

        void plus(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
