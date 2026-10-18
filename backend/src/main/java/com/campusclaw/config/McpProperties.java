package com.campusclaw.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** MCP 出站访问、凭据保护与超时配置。 */
@Validated
@ConfigurationProperties(prefix = "app.mcp")
public record McpProperties(
        @NotBlank String tokenEncryptionKey,
        @NotEmpty List<@NotBlank String> allowedHosts,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
