package com.zjl.worklog.credential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CredentialCryptoService 单元测试。
 * <p>
 * 每次测试前生成随机 256 位密钥，确保测试隔离。
 */
class CredentialCryptoServiceTest {

    private CredentialCryptoService cryptoService;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        // 每次测试生成新的随机 256 位密钥
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        byte[] keyBytes = keyGen.generateKey().getEncoded();
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        // 手动构造配置对象（绕过 Spring 容器，直接测试）
        CredentialCryptoProps props = new CredentialCryptoProps();
        props.setAesKey(base64Key);
        props.validate();

        cryptoService = new CredentialCryptoService(props);
    }

    @Test
    @DisplayName("加密解密往返：明文经过加密再解密应恢复原文")
    void encryptDecryptRoundTrip() {
        String plainText = "这是一个测试密码：P@ssw0rd!#2026";

        String encrypted = cryptoService.encrypt(plainText);
        assertNotNull(encrypted, "加密结果不应为空");
        assertNotEquals(plainText, encrypted, "加密结果不应等于明文");

        String decrypted = cryptoService.decrypt(encrypted);
        assertEquals(plainText, decrypted, "解密后应恢复原文");
    }

    @Test
    @DisplayName("同明文不同密文：GCM 随机 IV 保证每次加密结果不同")
    void samePlaintextProducesDifferentCiphertext() {
        String plainText = "相同的明文内容";

        String encrypted1 = cryptoService.encrypt(plainText);
        String encrypted2 = cryptoService.encrypt(plainText);

        assertNotEquals(encrypted1, encrypted2,
                "相同明文两次加密应产生不同密文（因为 IV 随机）");

        // 但两次密文解密后都应恢复原文
        assertEquals(plainText, cryptoService.decrypt(encrypted1));
        assertEquals(plainText, cryptoService.decrypt(encrypted2));
    }

    @Test
    @DisplayName("空串加密解密：空字符串也应能正常加解密")
    void encryptDecryptEmptyString() {
        String plainText = "";

        String encrypted = cryptoService.encrypt(plainText);
        assertNotNull(encrypted, "空串加密结果不应为空");

        String decrypted = cryptoService.decrypt(encrypted);
        assertEquals(plainText, decrypted, "空串解密后应恢复为空串");
    }

    @Test
    @DisplayName("篡改密文解密异常：修改密文后解密应抛出异常")
    void tamperedCiphertextThrowsException() {
        String plainText = "需要加密的敏感数据";
        String encrypted = cryptoService.encrypt(plainText);

        // 解码 -> 篡改一个字节 -> 重新编码
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        // 翻转密文中间一个字节（跳过前 12 字节 IV）
        if (decoded.length > 15) {
            decoded[15] ^= 0xFF;
        }
        String tampered = Base64.getEncoder().encodeToString(decoded);

        // GCM 的 AEAD 认证标签应检测到篡改并抛出异常
        assertThrows(Exception.class, () -> cryptoService.decrypt(tampered),
                "篡改后的密文解密应抛出异常（GCM 认证失败）");
    }
}