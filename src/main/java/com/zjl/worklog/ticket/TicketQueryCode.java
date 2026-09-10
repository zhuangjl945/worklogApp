package com.zjl.worklog.ticket;

import java.security.SecureRandom;

/**
 * 报修人查询密码（6 位数字）的规则中心：格式、弱口令、归一化、摘要比对都收在这里。
 *
 * <p>为什么要「报修人自己设一个 6 位数字」，而不是系统下发一长串随机令牌：
 * 原来的 32 位十六进制令牌没人记得住，只能靠截图或本机 localStorage，
 * 换台手机、清过缓存、用同事的手机扫码就查不了自己的工单。
 * 6 位数字是人能记住的长度，且每单不同——单号已经是可预测的短流水，
 * 这个密码就是唯一还在挡遍历的东西，所以它必填，也刻意<strong>不</strong>做成 sys_config 参数：
 * 参数化意味着管理员可以把它关掉或改成全员统一，那等于把查询接口公开。
 *
 * <p>与存量数据的兼容：库里存的一直是 {@code SHA-256(明文)}，
 * {@link #digest(String)} 只做「去掉所有空白再摘要」，与旧代码的 {@code sha256Hex(token.trim())}
 * 对不含空白的输入结果完全一致，因此老工单凭当初那串长令牌依然能查，不需要数据迁移。
 */
public final class TicketQueryCode {

    /** 查询密码固定 6 位：100 万种组合，配合查询失败锁定，遍历成本不可接受 */
    public static final int LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 一眼就能猜中的号段：全同、连号、重复两段，这些即使随机生成也要避开 */
    private static final String[] BLOCKED = {
            "000000", "111111", "222222", "333333", "444444",
            "555555", "666666", "777777", "888888", "999999",
            "123456", "234567", "345678", "456789", "654321",
            "543210", "987654", "876543", "765432", "121212",
            "123123", "112233", "010101", "000001", "100000"
    };

    private TicketQueryCode() {
    }

    /** 去掉所有空白（含全角空格以外的常规空白），供格式校验与摘要共用同一份归一化结果 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s", "");
    }

    /** 格式：必须是 6 位纯数字。长度不做参数化，理由见类注释 */
    public static boolean isFormatValid(String normalized) {
        if (normalized == null || normalized.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            // 只认 ASCII 数字：Character.isDigit 会把全角「４」和阿拉伯-印度数字也算数字，
            // 那样入库的摘要与下次输入的半角串根本对不上，报修人会稳定地「密码正确却查不到」
            char c = normalized.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 弱口令判定：全同、升/降连号、以及常见组合。太弱的号一个手机号就猜出来了 */
    public static boolean isWeak(String normalized) {
        if (normalized == null || normalized.length() != LENGTH) {
            return true;
        }
        for (String blocked : BLOCKED) {
            if (blocked.equals(normalized)) {
                return true;
            }
        }
        boolean allSame = true;
        boolean asc = true;
        boolean desc = true;
        for (int i = 1; i < normalized.length(); i++) {
            int prev = normalized.charAt(i - 1) - '0';
            int cur = normalized.charAt(i) - '0';
            if (cur != normalized.charAt(0) - '0') {
                allSame = false;
            }
            if (cur != prev + 1) {
                asc = false;
            }
            if (cur != prev - 1) {
                desc = false;
            }
        }
        return allSame || asc || desc;
    }

    /** 库里存的摘要：只存 SHA-256，明文不落库、不出库 */
    public static String digest(String raw) {
        return TicketTokens.sha256Hex(normalize(raw));
    }

    /** 定长摘要比对：库里没有明文，无法反查，只能拿对方填的算一次比一次 */
    public static boolean matches(String storedHash, String raw) {
        if (storedHash == null || storedHash.isEmpty() || normalize(raw).isEmpty()) {
            return false;
        }
        return storedHash.equals(digest(raw));
    }

    /** 随机 6 位数字，蜜罐假号与「帮我生成」兜底共用；避开弱口令 */
    public static String randomDigits() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format("%06d", RANDOM.nextInt(1_000_000));
            if (!isWeak(code)) {
                return code;
            }
        }
        return "802719";
    }
}