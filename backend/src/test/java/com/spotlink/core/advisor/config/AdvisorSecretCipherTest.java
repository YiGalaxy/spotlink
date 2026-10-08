package com.spotlink.advisor.config;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class AdvisorSecretCipherTest {
    private AdvisorSecretCipher cipher(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return new AdvisorSecretCipher(new AdvisorProperties(true,"http://ollama:11434/v1","qwen3:4b","",
                4096,120,"max_tokens",Base64.getEncoder().encodeToString(bytes)));
    }
    @Test void randomNoncesAndAuthenticatedEncryptionRequireTheOriginalMasterKey() {
        var cipher = cipher((byte)1);
        String first = cipher.encrypt("offline-secret"), second = cipher.encrypt("offline-secret");
        assertThat(first).isNotEqualTo(second).startsWith("v1:").doesNotContain("offline-secret");
        assertThat(cipher.decrypt(first)).isEqualTo("offline-secret");
        assertThatThrownBy(() -> cipher((byte)2).decrypt(first)).hasMessageContaining("恢复");
        byte[] tampered = Base64.getDecoder().decode(first.substring(3));
        tampered[tampered.length-1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("v1:"+Base64.getEncoder().encodeToString(tampered)))
                .hasMessageContaining("恢复");
    }
    @Test void missingMasterKeyDoesNotBlockAnUnconfiguredApplicationButRejectsCredentialWrites() {
        var cipher = new AdvisorSecretCipher(new AdvisorProperties(true,"http://ollama:11434/v1","qwen3:4b","",4096,120,"max_tokens",""));
        assertThat(cipher.ready()).isFalse();
        assertThat(cipher.encrypt("")).isNull();
        assertThatThrownBy(() -> cipher.encrypt("offline-secret")).hasMessageContaining("保存");
    }
}
