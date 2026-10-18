package com.campusclaw.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.config.McpProperties;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpTokenCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomNonceAndDecryptsBothEnvelopes() {
        McpTokenCipher cipher = cipher(KEY);

        String first = cipher.encrypt("secret-token");
        String second = cipher.encrypt("secret-token");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("secret-token");
        assertThat(cipher.decrypt(second)).isEqualTo("secret-token");
        assertThat(first).doesNotContain("secret-token");
    }

    @Test
    void rejectsInvalidKeysAndTamperedCiphertext() {
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");

        McpTokenCipher cipher = cipher(KEY);
        String envelope = cipher.encrypt("secret-token");
        String tampered = envelope.substring(0, envelope.length() - 2) + "AA";
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    private McpTokenCipher cipher(String key) {
        return new McpTokenCipher(new McpProperties(
                key, List.of("mcp-demo"), Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }
}
