package com.zjl.worklog.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("提交未选类型时落到渠道默认分类，而不是把 null 写进工作记录")
    void categoryFallsBackToChannelDefault() {
        assertEquals(8L, TicketCategoryResolver.resolve(null, 8L, cats(3L, 8L, 9L)));
    }

    @Test
    @DisplayName("渠道也没配默认时落到科室第一个启用分类")
    void categoryFallsBackToFirstEnabled() {
        assertEquals(3L, TicketCategoryResolver.resolve(null, null, cats(3L, 8L)));
    }

    @Test
    @DisplayName("报修人选了类型则用所选，不因渠道默认覆盖")
    void categoryKeepsReporterChoice() {
        assertEquals(9L, TicketCategoryResolver.resolve(9L, 8L, cats(3L, 8L, 9L)));
    }

    @Test
    @DisplayName("跨科室/已停用的类型直接拒绝，防止注入")
    void categoryRejectsUnknown() {
        try {
            TicketCategoryResolver.resolve(99L, 8L, cats(8L));
            fail("应拒绝无效类型");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("问题类型"));
        }
    }

    @Test
    @DisplayName("受理历史空分类工单：用渠道默认或科室分类，而不是插入 null")
    void acceptNullCategoryUsesFallback() {
        assertEquals(8L, TicketCategoryResolver.resolveForAccept(null, 8L, cats(8L, 9L)));
        assertEquals(8L, TicketCategoryResolver.resolveForAccept(null, null, cats(8L, 9L)));
    }

    @Test
    @DisplayName("科室没有任何分类时给出可执行错误，而不是 SQL 约束异常")
    void noCategoryGivesBizError() {
        try {
            TicketCategoryResolver.resolve(null, null, List.of());
            fail("应拒绝");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("工作分类"));
        }
    }

    @Test
    @DisplayName("联系电话：手机、短号、内线都能归一化，不足 3 位视为无效")
    void contactPhoneAcceptsShortAndInternal() {
        assertEquals("13800138000", TicketService.normalizeContactPhone("+86 138 0013 8000"));
        assertEquals("8012", TicketService.normalizeContactPhone("8012"));
        assertEquals("38012", TicketService.normalizeContactPhone("3-8012"));
        assertEquals("", TicketService.normalizeContactPhone("  "));
        assertTrue(TicketService.normalizeContactPhone("12").length() < 3);
        assertTrue(TicketService.normalizeContactPhone("8001").length() >= 3);
    }

    @Test
    @DisplayName("单号：ST + 自增 ID 基数，从 ST100001 起，不再靠数当天有几天")
    void shortTicketNoComesFromAutoIncrementId() {
        TicketNoGenerator generator = new TicketNoGenerator();
        assertEquals("ST100001", generator.format(1L));
        assertEquals("ST100002", generator.format(2L));
        assertEquals("ST100500", generator.format(500L));
        // ID 是唯一来源，所以两个不同的工单不可能拿到同一个号，也就不需要撞键重试
        assertFalse(generator.format(7L).equals(generator.format(8L)));
        // 号是纯数字尾巴，抄在纸上不含 1/O、0/D 这类歧义字符
        assertTrue(generator.format(9L).substring(2).matches("\\d+"));
        assertThrows(IllegalStateException.class, () -> generator.format(null));
    }

    @Test
    @DisplayName("蜜罐假号与真号形状一致，不给机器人「我已被识别」的反馈")
    void honeypotFakeNoLooksReal() {
        TicketNoGenerator generator = new TicketNoGenerator();
        for (int i = 0; i < 20; i++) {
            String fake = generator.fakeNo();
            assertTrue(fake.matches("ST\\d{6}"), "假号形状走样：" + fake);
            // 尾巴那 6 位本身就是「合法查询密码的形状」，假号和真号在格式上分不出来
            assertTrue(TicketQueryCode.isFormatValid(fake.substring(2)), "假号尾数不合法：" + fake);
        }
    }

    @Test
    @DisplayName("查询密码只收 6 位纯数字，且必填语义由长度决定")
    void queryCodeMustBeSixDigits() {
        assertTrue(TicketQueryCode.isFormatValid("472815"));
        assertFalse(TicketQueryCode.isFormatValid(null));
        assertFalse(TicketQueryCode.isFormatValid(""));
        assertFalse(TicketQueryCode.isFormatValid("47281"));
        assertFalse(TicketQueryCode.isFormatValid("4728159"));
        assertFalse(TicketQueryCode.isFormatValid("47281a"));
        // 中文输入法打出来的全角数字：Character.isDigit 会放它过去，但那串与半角摘要不同，
        // 收下就是「报修人每次都输对却每次都查不到」
        assertFalse(TicketQueryCode.isFormatValid("４７２８１５"));
        assertFalse(TicketQueryCode.isFormatValid("٤٧٢٨١٥"));
        // 空白当场吃掉：报修人从别处复制时带个空格、换行，不该变成「密码错误」
        assertEquals("472815", TicketQueryCode.normalize(" 472 815 \n"));
        // 但归一化只吃空白、不吃其它字符：老工单的明文是十六进制长令牌，
        // 这里要是顺手删掉非数字，那些存量摘要就再也对不上了。带横杠的输入直接判格式不合法
        assertEquals("47-2815", TicketQueryCode.normalize("47-2815"));
        assertFalse(TicketQueryCode.isFormatValid(TicketQueryCode.normalize("47-2815")));
        // 「只留数字并截到 6 位」是前端输入框（normalizeQueryCode）做的事，服务端只做判定不做清洗：
        // 清洗会动到摘要口径，存量数据就对不上了
    }

    @Test
    @DisplayName("弱口令：全同、连号、常见组合一律拒绝")
    void weakQueryCodesAreRejected() {
        for (String weak : new String[]{"000000", "111111", "123456", "654321", "234567",
                "987654", "121212", "112233", "010101"}) {
            assertTrue(TicketQueryCode.isWeak(weak), "应判为弱口令：" + weak);
        }
        for (String ok : new String[]{"472815", "802719", "135799", "975310"}) {
            assertFalse(TicketQueryCode.isWeak(ok), "误伤正常口令：" + ok);
        }
        // 位数不对本身就判弱，别让调用方忘了先查格式
        assertTrue(TicketQueryCode.isWeak("12345"));
        assertTrue(TicketQueryCode.isWeak(null));
    }

    @Test
    @DisplayName("随机生成的查询密码不会落在自己的黑名单里")
    void randomQueryCodeIsNeverWeak() {
        for (int i = 0; i < 50; i++) {
            String code = TicketQueryCode.randomDigits();
            assertTrue(TicketQueryCode.isFormatValid(code), "位数不对：" + code);
            assertFalse(TicketQueryCode.isWeak(code), "生成了弱口令：" + code);
        }
    }

    @Test
    @DisplayName("库里只存摘要：归一化规则与旧实现兼容，存量长令牌照样验得过")
    void digestStaysCompatibleWithLegacyTokens() {
        // 老工单的明文是 32 位十六进制长令牌，历史上按 sha256Hex(token.trim()) 入库
        String legacyToken = TicketTokens.randomToken();
        assertEquals(TicketTokens.sha256Hex(legacyToken), TicketQueryCode.digest(legacyToken));
        assertTrue(TicketQueryCode.matches(TicketTokens.sha256Hex(legacyToken), "  " + legacyToken + " "));
        // 新工单：6 位数字同样只存摘要，明文不可反查
        assertTrue(TicketQueryCode.matches(TicketQueryCode.digest("472815"), "472815"));
        assertFalse(TicketQueryCode.matches(TicketQueryCode.digest("472815"), "472816"));
        assertFalse(TicketQueryCode.matches(TicketQueryCode.digest("472815"), ""));
        assertFalse(TicketQueryCode.matches(null, "472815"));
        assertFalse(TicketQueryCode.matches("", "472815"));
    }

    @Test
    @DisplayName("限流的 blocked() 只判断不扣配额，免得正常刷新把自己锁在门外")
    void blockedPeeksWithoutConsumingQuota() {
        TicketRateLimiter limiter = new TicketRateLimiter();
        assertFalse(limiter.blocked("query:no:ST100001", 2, 60));
        assertTrue(limiter.acquire("query:no:ST100001", 2, 60));
        assertTrue(limiter.acquire("query:no:ST100001", 2, 60));
        // 连着看十次也不会因为「看」而被锁
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.blocked("query:no:ST100001", 2, 60));
        }
        // 从没失败过的维度不受影响；maxCount<=0 表示这道锁不启用
        assertFalse(limiter.blocked("query:ip:10.0.0.9", 2, 60));
        assertFalse(limiter.blocked("query:no:ST100001", 0, 60));
    }

    @Test
    @DisplayName("公开视图不得携带联系方式：单号可预测，能吐出去的就只有这个视图")
    void publicViewCarriesNoPersonalContactFields() {
        // 报修人手机号/姓名/来源 IP 一旦进到这个视图，猜中 6 位密码就等于拿到了个人信息，
        // 而密码只有 100 万种组合，靠的正是「猜中也看不到别的」
        java.util.Set<String> forbidden = java.util.Set.of(
                "contactName", "contactPhone", "submitIp", "submitUa", "deptId", "assigneeId",
                "bizDeptId", "channelId", "categoryId", "workRecordId", "accessTokenHash");
        for (java.lang.reflect.Field f : com.zjl.worklog.ticket.dto.TicketPublicView.class.getDeclaredFields()) {
            assertFalse(forbidden.contains(f.getName()),
                    "TicketPublicView 不该有字段：" + f.getName());
        }
        // 反向确认这份清单不是空谈：这些字段确实在实体里，只是不该出现在对外视图
        java.util.Set<String> entityFields = new java.util.HashSet<>();
        for (java.lang.reflect.Field f : com.zjl.worklog.ticket.entity.ServiceTicketEntity.class.getDeclaredFields()) {
            entityFields.add(f.getName());
        }
        assertTrue(entityFields.contains("contactPhone"));
        assertTrue(entityFields.contains("submitIp"));
    }

    private static java.util.List<com.zjl.worklog.work.entity.WorkCategory> cats(Long... ids) {
        java.util.ArrayList<com.zjl.worklog.work.entity.WorkCategory> list = new java.util.ArrayList<>();
        for (Long id : ids) {
            com.zjl.worklog.work.entity.WorkCategory c = new com.zjl.worklog.work.entity.WorkCategory();
            c.setId(id);
            list.add(c);
        }
        return list;
    }
}
