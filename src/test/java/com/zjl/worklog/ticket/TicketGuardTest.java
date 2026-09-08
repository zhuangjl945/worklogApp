package com.zjl.worklog.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防刷与越权读图这两个安全不变量的单测 */
class TicketGuardTest {

    private static final String DIR = "work-records/";

    @Test
    @DisplayName("完整访问地址能归一化成 objectKey，非法输入返回空串")
    void toKeyNormalizesUrlAndRejectsGarbage() {
        // 前端 uploadToOss() 返回的就是这种完整地址
        assertEquals("work-records/tickets/AB12CD34/2026/09/x.jpg",
                TicketImageKeys.toKey("https://my-bucket.oss-cn-hangzhou.aliyuncs.com/work-records/tickets/AB12CD34/2026/09/x.jpg"));
        // 带签名参数的地址要能去掉 query，否则前缀比对与取图都会失败
        assertEquals("work-records/tickets/AB12CD34/2026/09/x.jpg",
                TicketImageKeys.toKey("https://h.com/work-records/tickets/AB12CD34/2026/09/x.jpg?Expires=1&Signature=abc"));
        // 本来就是 key，原样保留
        assertEquals("work-records/tickets/AB12CD34/x.jpg",
                TicketImageKeys.toKey("work-records/tickets/AB12CD34/x.jpg"));
        // 只有域名没有路径：视为非法
        assertEquals("", TicketImageKeys.toKey("https://h.com"));
        assertEquals("", TicketImageKeys.toKey(null));
        assertEquals("", TicketImageKeys.toKey("   "));
        // 归一化后仍可被渠道前缀校验拦住：防止把完整 URL 当绕过手段
        assertFalse(TicketImageKeys.belongsToChannel(DIR, "AB12CD34",
                "https://h.com/work-records/tickets/FF00FF00/2026/09/x.jpg"));
        assertTrue(TicketImageKeys.belongsToChannel(DIR, "AB12CD34",
                "https://h.com/work-records/tickets/AB12CD34/2026/09/x.jpg"));
    }


    @Test
    @DisplayName("图片 key 必须落在本渠道目录下，否则视为越权")
    void imageKeyMustBelongToChannel() {
        assertTrue(TicketImageKeys.belongsToChannel(DIR, "AB12CD34",
                "work-records/tickets/AB12CD34/2026/09/x.jpg"));
        // 别的渠道的图片
        assertFalse(TicketImageKeys.belongsToChannel(DIR, "AB12CD34",
                "work-records/tickets/FF00FF00/2026/09/x.jpg"));
        // 渠道目录之外的对象
        assertFalse(TicketImageKeys.belongsToChannel(DIR, "AB12CD34",
                "work-records/2026/09/x.jpg"));
        // 空值
        assertFalse(TicketImageKeys.belongsToChannel(DIR, "AB12CD34", null));
        assertFalse(TicketImageKeys.belongsToChannel(DIR, "AB12CD34", " "));
    }

    @Test
    @DisplayName("渠道码里的非法字符会被剔除，不能靠渠道码做路径穿越")
    void channelCodeCannotEscapeDirectory() {
        String prefix = TicketImageKeys.channelPrefix(DIR, "../../etc");
        assertFalse(prefix.contains(".."), "前缀里不应残留穿越符号：" + prefix);
        assertTrue(prefix.startsWith("work-records/tickets/"), "实际=" + prefix);
    }

    @Test
    @DisplayName("限流：窗口内超量后拒绝，不同维度互不影响")
    void rateLimiterBlocksOverLimit() {
        TicketRateLimiter limiter = new TicketRateLimiter();
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.acquire("submit:ip:10.0.0.1", 3, 60), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(limiter.acquire("submit:ip:10.0.0.1", 3, 60), "第 4 次应被拒");
        // 换 IP 不受影响
        assertTrue(limiter.acquire("submit:ip:10.0.0.2", 3, 60));
    }

    @Test
    @DisplayName("限流：窗口滑过后重新放行")
    void rateLimiterRecoversAfterWindow() throws InterruptedException {
        TicketRateLimiter limiter = new TicketRateLimiter();
        assertTrue(limiter.acquire("submit:ch:9", 1, 1));
        assertFalse(limiter.acquire("submit:ch:9", 1, 1));
        Thread.sleep(1100L);
        assertTrue(limiter.acquire("submit:ch:9", 1, 1));
    }

    @Test
    @DisplayName("form token 一次性：消费后再用即失效")
    void formTokenIsSingleUse() {
        TicketFormTokenService service = new TicketFormTokenService();
        String token = service.issue(7L, "10.0.0.1");
        assertEquals(7L, service.require(token));

        String other = service.issue(8L, "10.0.0.2");
        service.consume(other);
        try {
            service.require(other);
            fail("已消费的令牌不应再可用");
        } catch (RuntimeException expected) {
            // 令牌被拒时抛的是 BizException，这里只关心它确实被拒
            assertTrue(expected.getMessage() != null);
        }
    }

    @Test
    @DisplayName("令牌是随机串且库里只存摘要：同一输入的摘要稳定、不同输入不同")
    void sha256IsStableAndDistinct() {
        assertEquals(TicketTokens.sha256Hex("abc"), TicketTokens.sha256Hex("abc"));
        assertFalse(TicketTokens.sha256Hex("abc").equals(TicketTokens.sha256Hex("abd")));
        assertEquals(64, TicketTokens.sha256Hex("abc").length());
        assertTrue(TicketTokens.randomToken().length() == 32);
        assertTrue(TicketTokens.randomChannelCode().length() == 8);
    }
}
