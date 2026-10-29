package com.zjl.worklog.credential;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据保管库 AES-256-GCM 加解密服务。
 * <p>
 * 用于加密存储密码、API 密钥等敏感凭据。
 * 密文格式：Base64( IV(12B) || CipherText+Tag(16B) )
 */
@Service
public class CredentialCryptoService {

    /** GCM 模式推荐的 IV 长度（字节） */
    private static final int IV_LENGTH_BYTES = 12;

    /** GCM 认证标签长度（位） */
    private static final int TAG_LENGTH_BITS = 128;

    /** 算法标识 */
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom;

    public CredentialCryptoService(CredentialCryptoProps props) {
        this.secretKey = new SecretKeySpec(props.getKeyBytes(), ALGORITHM);
        this.secureRandom = new SecureRandom();
    }

    /**
     * 加密明文。
     *
     * @param plainText 待加密的明文
     * @return Base64 编码的密文（包含 IV 和认证标签）
     */
    public String encrypt(String plainText) {
        try {
            // 生成 12 字节随机 IV
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            // 初始化 Cipher 为加密模式
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            // 执行加密
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV + 密文（含 Tag）
            byte[] combined = new byte[IV_LENGTH_BYTES + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(cipherText, 0, combined, IV_LENGTH_BYTES, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("凭据加密失败", e);
        }
    }

    /**
     * 解密密文。
     *
     * @param cipherText Base64 编码的密文
     * @return 解密后的明文
     */
    public String decrypt(String cipherText) {
        try {
            // Base64 解码
            byte[] combined = Base64.getDecoder().decode(cipherText);

            // 拆分 IV（前 12 字节）和密文（剩余部分）
            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

            byte[] encrypted = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, encrypted, 0, encrypted.length);

            // 初始化 Cipher 为解密模式
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            // 执行解密（GCM 会自动验证认证标签）
            byte[] plainText = cipher.doFinal(encrypted);

            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("凭据解密失败", e);
        }
    }
}