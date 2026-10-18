package com.campusclaw.mcp;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.config.McpProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 使用版本化 AES-GCM 信封保护必须可逆使用的 MCP Bearer Token。 */
@Component
public class McpTokenCipher {
    private static final String VERSION = "v1:";
    private static final int IV_LENGTH = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public McpTokenCipher(McpProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.tokenEncryptionKey());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MCP token encryption key must be valid Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("MCP token encryption key must contain exactly 32 bytes");
        }
        key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String token) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = Arrays.copyOf(iv, iv.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
            return VERSION + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt MCP token", exception);
        }
    }

    public String decrypt(String envelope) {
        try {
            if (envelope == null || !envelope.startsWith(VERSION)) {
                throw new GeneralSecurityException("Unsupported MCP token envelope");
            }
            byte[] bytes = Base64.getDecoder().decode(envelope.substring(VERSION.length()));
            if (bytes.length <= IV_LENGTH) {
                throw new GeneralSecurityException("Invalid MCP token envelope");
            }
            byte[] iv = Arrays.copyOfRange(bytes, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(bytes, IV_LENGTH, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new DependencyUnavailableException("MCP credential");
        }
    }
}
