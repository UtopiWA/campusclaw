package com.campusclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String uploadDir, long maxUploadBytes, DemoSeed demoSeed) {

    public record DemoSeed(boolean enabled, String password) {
    }
}
