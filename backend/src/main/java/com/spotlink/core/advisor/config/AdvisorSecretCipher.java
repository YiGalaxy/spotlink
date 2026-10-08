package com.spotlink.advisor.config;

import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** 随机 nonce + 认证附加数据：数据库备份无法在没有独立主密钥时还原 API Key。 */
@Component
public class AdvisorSecretCipher {
    private static final byte[] AAD = "spotlink:platform-advisor:v1".getBytes(StandardCharsets.UTF_8);
    private final AdvisorProperties properties;

    public AdvisorSecretCipher(AdvisorProperties properties) { this.properties = properties; }

    public boolean ready() {
        try { return Base64.getDecoder().decode(properties.configSecret()).length == 32; }
        catch (RuntimeException e) { return false; }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (Exception e) { throw unavailable(); }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return "";
        try {
            if (!stored.startsWith("v1:")) throw new IllegalArgumentException();
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(stored.substring(3)));
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce).doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) { throw unavailable(); }
    }

    private Cipher cipher(int mode, byte[] nonce) throws Exception {
        if (!ready()) throw new IllegalStateException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(Base64.getDecoder().decode(properties.configSecret()), "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD);
        return cipher;
    }

    private BusinessException unavailable() {
        return BusinessException.of(ResultCode.ADVISOR_UNAVAILABLE,
                "模型凭证无法解密或保存，请管理员恢复 SPOTLINK_ADVISOR_CONFIG_SECRET，或重新填写密钥");
    }
}
