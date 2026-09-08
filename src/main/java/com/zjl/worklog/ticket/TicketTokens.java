package com.zjl.worklog.ticket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 工单相关的随机标识与摘要工具。
 *
 * <p>登记链接不再依赖印在二维码里的密钥：墙上的二维码本来就是给所有人扫的，
 * 密钥印出去就没有秘密可言。改为「渠道码即能力 + 服务端签发短期 form token」，
 * 由 {@link TicketFormTokenService} 负责时效与一次性。
 */
public final class TicketTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TicketTokens() {
    }

    /** SHA-256 十六进制摘要：用于保存报修人查询令牌，库里不落明文 */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算摘要失败", e);
        }
    }

    /** 生成 URL 安全的随机十六进制串 */
    public static String randomHex(int byteLength) {
        byte[] bytes = new byte[Math.max(1, byteLength)];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 渠道编码：8 位十六进制，不可枚举，够短便于印在台卡上 */
    public static String randomChannelCode() {
        return randomHex(4).toUpperCase();
    }

    /** 报修人查询令牌 / form token */
    public static String randomToken() {
        return randomHex(16);
    }
}
