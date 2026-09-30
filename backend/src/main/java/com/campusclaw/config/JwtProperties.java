package com.campusclaw.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 定义服务端 JWT 签名与有效期；签名密钥不得提供可运行默认值。 */
@Validated
@ConfigurationProperties(prefix = "app.auth.jwt")
public record JwtProperties(@NotBlank String secret, @NotNull Duration ttl) {

    @AssertTrue(message = "JWT secret must contain at least 32 UTF-8 bytes")
    public boolean isSecretLengthValid() {
        return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    @AssertTrue(message = "JWT TTL must be positive")
    public boolean isTtlPositive() {
        return ttl != null && !ttl.isZero() && !ttl.isNegative();
    }
}
