package com.zjl.worklog.credential;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 凭据保管库 AES-256 加密配置属性。
 * <p>
 * 从 application.yml 的 credential 前缀读取配置，
 * aes-key 必须是 Base64 编码的 32 字节密钥。
 */
@Component
@ConfigurationProperties(prefix = "credential")
public class CredentialCryptoProps {

    /** Base64 编码的 32 字节 AES-256 密钥 */
    private String aesKey;

    /** 解码后的原始密钥字节（32 字节） */
    private byte[] keyBytes;

    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }

    public byte[] getKeyBytes() {
        return keyBytes;
    }

    /**
     * 启动时校验密钥配置：
     * - aesKey 不能为空
     * - Base64 解码后必须恰好 32 字节（256 位）
     * 校验失败抛出 IllegalStateException 阻止应用启动
     */
    @PostConstruct
    void validate() {
        if (aesKey == null || aesKey.isBlank()) {
            throw new IllegalStateException(
                    "凭据保管库密钥未配置：credential.aes-key 不能为空，请设置环境变量 CREDENTIAL_AES_KEY");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(aesKey);
            if (decoded.length != 32) {
                throw new IllegalStateException(
                        "凭据保管库密钥长度错误：Base64 解码后为 " + decoded.length + " 字节，要求恰好 32 字节（256 位）");
            }
            this.keyBytes = decoded;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "凭据保管库密钥格式错误：credential.aes-key 不是合法的 Base64 字符串", e);
        }
    }
}