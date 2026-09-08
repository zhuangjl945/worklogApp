package com.zjl.worklog.security;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@Data
@ConfigurationProperties(prefix = "jwt")
public class JwtProps implements InitializingBean {

    /** 不允许出现的脚手架默认密钥前缀，命中即拒绝启动 */
    private static final String PLACEHOLDER_PREFIX = "change-me";

    /** HS256 要求密钥不少于 256 bit = 32 字节 */
    private static final int MIN_SECRET_BYTES = 32;

    private String secret;

    private long expireSeconds = 7200;

    /**
     * 启动期强校验：密钥不合格直接让应用起不来。
     *
     * <p>理由：密钥为默认值时，任何人都能自行签发任意 userId 的 token 冒充管理员，
     * 这种错误必须「响」而不是静默运行。上线前请先配置环境变量 JWT_SECRET。
     */
    @Override
    public void afterPropertiesSet() {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException(
                    "jwt.secret 未配置。请设置环境变量 JWT_SECRET（至少 32 字符的强随机串），"
                            + "生成方式见 application.yml.example 中的说明。");
        }
        if (secret.startsWith(PLACEHOLDER_PREFIX)) {
            throw new IllegalStateException("jwt.secret 仍是脚手架默认值，请替换为强随机密钥（环境变量 JWT_SECRET）");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret 不足 " + MIN_SECRET_BYTES + " 字节，无法安全使用 HS256 签名");
        }
        if (expireSeconds <= 0) {
            throw new IllegalStateException("jwt.expire-seconds 必须为正数");
        }
        if (expireSeconds > 86400) {
            throw new IllegalStateException("jwt.expire-seconds 不建议超过 86400（1 天）");
        }
    }
}
