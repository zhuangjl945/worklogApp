package com.zjl.worklog.auth;

import com.zjl.worklog.common.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 登录验证码。答案只放在内存里，两分钟过期，校验一次即作废。
 */
@Component
public class LoginCaptchaService {

    public static final int CODE_INVALID = 4003;

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 4;
    private static final int MAX_CHALLENGES = 5_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final long ttlMillis;
    private final Supplier<String> codes;
    private final ConcurrentHashMap<String, Challenge> challenges = new ConcurrentHashMap<>();

    @Autowired
    public LoginCaptchaService() {
        this(Clock.systemUTC(), 120_000L, LoginCaptchaService::randomCode);
    }

    LoginCaptchaService(Clock clock, long ttlMillis, Supplier<String> codes) {
        this.clock = clock;
        this.ttlMillis = ttlMillis;
        this.codes = codes;
    }

    public Issued issue() {
        purgeExpired();
        if (challenges.size() >= MAX_CHALLENGES) {
            throw new BizException(LoginGuard.CODE_RATE_LIMITED, "操作过于频繁，请稍后再试");
        }
        String code = codes.get();
        String id = UUID.randomUUID().toString().replace("-", "");
        challenges.put(id, new Challenge(code, clock.millis() + ttlMillis));
        return new Issued(id, render(code));
    }

    /** 校验并消费。错误、过期、重复使用都是同一句提示。 */
    public void verifyAndConsume(String id, String code) {
        String key = id == null ? "" : id.trim();
        Challenge challenge = key.isEmpty() ? null : challenges.remove(key);
        String given = code == null ? "" : code.trim();
        if (challenge == null
                || clock.millis() > challenge.expireAt
                || !sameCode(challenge.code, given)) {
            throw new BizException(CODE_INVALID, "验证码错误或已过期");
        }
    }

    private void purgeExpired() {
        long now = clock.millis();
        challenges.entrySet().removeIf(entry -> entry.getValue().expireAt < now);
    }

    private static boolean sameCode(String expected, String given) {
        if (expected == null || given.length() != expected.length()) {
            return false;
        }
        byte[] left = expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] right = given.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }

    static String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    private static String render(String code) {
        int width = 112;
        int height = 40;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(244, 246, 248));
            g.fillRect(0, 0, width, height);
            ThreadLocalRandom noise = ThreadLocalRandom.current();
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 5; i++) {
                g.setColor(new Color(170 + noise.nextInt(60), 176 + noise.nextInt(50), 182 + noise.nextInt(50)));
                g.drawLine(noise.nextInt(width), noise.nextInt(height), noise.nextInt(width), noise.nextInt(height));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(25 + noise.nextInt(70), 40 + noise.nextInt(60), 55 + noise.nextInt(70)));
                g.drawString(String.valueOf(code.charAt(i)), 12 + i * 24, 27 + noise.nextInt(5));
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("没有可用的 PNG 编码器");
            }
        } catch (IOException e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    public record Issued(String id, String image) {
    }

    private record Challenge(String code, long expireAt) {
    }
}
