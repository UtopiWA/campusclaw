package com.campusclaw.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 集中声明应用与外部检索依赖配置，避免在业务代码中读取环境变量或硬编码凭据。 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String uploadDir,
        @Min(1) long maxUploadBytes,
        @Valid @NotNull DemoSeed demoSeed,
        @Valid @NotNull Qdrant qdrant,
        @Valid @NotNull Embedding embedding,
        @Valid @NotNull Chat chat,
        @Valid @NotNull Retrieval retrieval) {

    public record DemoSeed(boolean enabled, String password) {
    }

    public record Qdrant(@NotBlank String url, @NotBlank String collection) {
    }

    public record Embedding(
            @NotBlank String baseUrl,
            @NotBlank String apiKey,
            @NotBlank String model,
            @NotNull @Min(1) Integer dimension) {
    }

    public record Chat(
            @NotBlank String baseUrl,
            @NotBlank String apiKey,
            @NotBlank String model) {
    }

    public record Retrieval(
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Min(1) int queryMaxCodePoints,
            @Min(1) int defaultLimit,
            @Min(1) @Max(100) int maxLimit,
            @DecimalMin("0.0") @DecimalMax("1.0") double vectorThreshold,
            @Min(1) int rrfK,
            boolean initializeVectorStore) {
    }
}
