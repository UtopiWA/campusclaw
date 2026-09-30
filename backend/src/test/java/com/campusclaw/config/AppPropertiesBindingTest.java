package com.campusclaw.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AppPropertiesBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.upload-dir=./uploads",
                    "app.max-upload-bytes=1024",
                    "app.demo-seed.enabled=false",
                    "app.qdrant.url=http://qdrant:6333",
                    "app.qdrant.collection=test",
                    "app.embedding.base-url=https://embedding.example/v1",
                    "app.embedding.model=embed",
                    "app.embedding.dimension=3",
                    "app.chat.base-url=https://chat.example/v1",
                    "app.chat.api-key=chat-secret-value",
                    "app.chat.model=chat",
                    "app.retrieval.connect-timeout=1s",
                    "app.retrieval.read-timeout=2s",
                    "app.retrieval.query-max-code-points=1000",
                    "app.retrieval.default-limit=10",
                    "app.retrieval.max-limit=20",
                    "app.retrieval.vector-threshold=0.35",
                    "app.retrieval.rrf-k=60");

    @Test
    void missingEmbeddingKeyFailsBindingWithoutEchoingOtherSecrets() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root.getMessage()).contains("app.embedding", "apiKey");
            assertThat(root.getMessage()).doesNotContain("chat-secret-value");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    static class PropertiesConfiguration {
    }
}
