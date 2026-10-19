package com.campusclaw.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.config.AppProperties;
import com.campusclaw.gateway.ChatGateway.ChatMessage;
import com.campusclaw.gateway.VectorStoreGateway.VectorPoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayClientTest {
    private HttpServer server;
    private ExecutorService executor;
    private String baseUrl;
    private final AtomicReference<String> vectorRequest = new AtomicReference<>();
    private final AtomicReference<String> embeddingAuthorization = new AtomicReference<>();
    private final AtomicReference<String> chatAuthorization = new AtomicReference<>();
    private final AtomicReference<String> chatRequest = new AtomicReference<>();
    private final AtomicInteger providerErrorCalls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/embeddings", exchange -> {
            embeddingAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            json(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            chatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            chatRequest.set(readBody(exchange));
            json(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"依据回答 [1]\"}}]}");
        });
        server.createContext("/provider-error/embeddings", exchange -> {
            providerErrorCalls.incrementAndGet();
            json(exchange, 401, "{\"error\":\"provider leaked body\"}");
        });
        server.createContext("/wrong-count/embeddings", exchange -> json(exchange, 200,
                "{\"data\":[]}"));
        server.createContext("/wrong-dimension/embeddings", exchange -> json(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}"));
        server.createContext("/slow/embeddings", exchange -> {
            try {
                Thread.sleep(250);
                json(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/empty/chat/completions", exchange -> json(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"));
        server.createContext("/provider-error/chat/completions", exchange ->
                json(exchange, 500, "{\"error\":\"provider leaked body\"}"));
        server.createContext("/slow/chat/completions", exchange -> {
            try {
                Thread.sleep(250);
                json(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"迟到回答\"}}]}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/stream/chat/completions", exchange -> {
            chatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            chatRequest.set(readBody(exchange));
            sse(exchange,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"第一段\"}}]}\n\n"
                            + ": heartbeat\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"与第二段\"}}]}\n\n"
                            + "data: [DONE]\n\n",
                    7);
        });
        server.createContext("/malformed-stream/chat/completions", exchange ->
                sse(exchange, "data: {not-json}\n\ndata: [DONE]\n\n", 5));
        server.createContext("/truncated-stream/chat/completions", exchange ->
                sse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}\n\n", 4));
        server.createContext("/stalled-stream/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"部分\"}}]}\n\n"
                            .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(250);
                exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/collections/test", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/points/search")) {
                vectorRequest.set(readBody(exchange));
                json(exchange, 200, "{\"result\":[{\"id\":9,\"score\":0.8,\"payload\":{\"chunk_id\":9,\"class_id\":2}}]}");
            } else if (path.endsWith("/points")) {
                vectorRequest.set(readBody(exchange));
                json(exchange, 200, "{\"status\":\"ok\"}");
            } else {
                json(exchange, 200, "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void validatesEmbeddingAndChatResponses() {
        AppProperties properties = properties();
        GatewayRestClientFactory factory = new GatewayRestClientFactory(properties);

        assertThat(new OpenAiEmbeddingGateway(properties, factory).embed(List.of("短文本")))
                .containsExactly(List.of(0.1, 0.2, 0.3));
        assertThat(embeddingAuthorization.get()).isEqualTo("Bearer embedding-secret");
        assertThat(new OpenAiChatGateway(properties, factory).complete(
                List.of(new ChatMessage("system", "服务端规则"), new ChatMessage("user", "问题"))))
                .isEqualTo("依据回答 [1]");
        assertThat(chatAuthorization.get()).isEqualTo("Bearer chat-secret");
        assertThat(chatRequest.get()).contains("\"reasoning_effort\":\"none\"", "\"stream\":false");
    }

    @Test
    void embeddingRejectsProviderErrorsTimeoutCountAndDimensionWithoutLeakingDetails() {
        assertUnavailable(new OpenAiEmbeddingGateway(
                properties(baseUrl + "/provider-error", baseUrl + "/v1", Duration.ofSeconds(2)),
                new GatewayRestClientFactory(properties(
                        baseUrl + "/provider-error", baseUrl + "/v1", Duration.ofSeconds(2)))));
        assertThat(providerErrorCalls).hasValue(2);

        assertUnavailable(embeddingGateway("/wrong-count", Duration.ofSeconds(2)));
        assertUnavailable(embeddingGateway("/wrong-dimension", Duration.ofSeconds(2)));
        assertUnavailable(embeddingGateway("/slow", Duration.ofMillis(50)));
    }

    @Test
    void chatRejectsEmptyAndProviderErrorResponses() {
        assertThatThrownBy(() -> chatGateway("/empty").complete(
                List.of(new ChatMessage("user", "问题"))))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable");
        assertThatThrownBy(() -> chatGateway("/provider-error").complete(
                List.of(new ChatMessage("user", "问题"))))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable")
                .hasMessageNotContaining("provider leaked body")
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining("chat-secret");
        assertThatThrownBy(() -> chatGateway("/slow", Duration.ofMillis(50)).complete(
                List.of(new ChatMessage("user", "问题"))))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable");
    }

    @Test
    void streamsIncrementalUtf8AndRejectsMalformedOrTruncatedSse() {
        OpenAiChatGateway gateway = chatGateway("/stream");
        List<String> deltas = new ArrayList<>();
        try (ChatGateway.ChatStream stream = gateway.openStream(
                List.of(new ChatMessage("system", "规则"), new ChatMessage("user", "问题")))) {
            stream.consume(deltas::add);
        }

        assertThat(deltas).containsExactly("第一段", "与第二段");
        assertThat(chatAuthorization.get()).isEqualTo("Bearer chat-secret");
        assertThat(chatRequest.get()).contains("\"reasoning_effort\":\"none\"", "\"stream\":true");
        assertThatThrownBy(() -> {
            try (ChatGateway.ChatStream stream = chatGateway("/malformed-stream")
                    .openStream(List.of(new ChatMessage("user", "问题")))) {
                stream.consume(value -> { });
            }
        }).isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable");
        assertThatThrownBy(() -> {
            try (ChatGateway.ChatStream stream = chatGateway("/truncated-stream")
                    .openStream(List.of(new ChatMessage("user", "问题")))) {
                stream.consume(value -> { });
            }
        }).isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable");
        assertThatThrownBy(() -> chatGateway("/provider-error")
                .openStream(List.of(new ChatMessage("user", "不得泄露的问题正文"))))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable")
                .hasMessageNotContaining("provider leaked body")
                .hasMessageNotContaining("chat-secret")
                .hasMessageNotContaining("不得泄露");
        assertThatThrownBy(() -> chatGateway("/slow", Duration.ofMillis(50))
                .openStream(List.of(new ChatMessage("user", "问题"))))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable");
        assertThatThrownBy(() -> {
            try (ChatGateway.ChatStream stream = chatGateway("/stalled-stream", Duration.ofMillis(50))
                    .openStream(List.of(new ChatMessage("user", "问题")))) {
                stream.consume(value -> { });
            }
        }).isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Chat service is temporarily unavailable");
    }

    @Test
    void qdrantUsesNumberOnlyPayloadAndClassFilter() {
        AppProperties properties = properties();
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(
                properties, new GatewayRestClientFactory(properties));
        gateway.ensureCollection();
        gateway.upsert(List.of(new VectorPoint(9L, List.of(0.1, 0.2, 0.3), 2L, 3L, 4L, 0)));

        assertThat(vectorRequest.get()).contains("\"chunk_id\":9", "\"class_id\":2")
                .doesNotContain("chunk_text", "短文本");
        assertThat(gateway.search(List.of(0.1, 0.2, 0.3), 2L, 10, 0.35))
                .singleElement().satisfies(hit -> {
                    assertThat(hit.chunkId()).isEqualTo(9L);
                    assertThat(hit.score()).isEqualTo(0.8);
                });
        assertThat(vectorRequest.get()).contains("\"key\":\"class_id\"", "\"value\":2");
    }

    private AppProperties properties() {
        return properties(baseUrl + "/v1", baseUrl + "/v1", Duration.ofSeconds(2));
    }

    private AppProperties properties(String embeddingBaseUrl, String chatBaseUrl, Duration readTimeout) {
        return new AppProperties("./uploads", 1024, new AppProperties.DemoSeed(false, ""),
                new AppProperties.Qdrant(baseUrl, "test"),
                new AppProperties.Embedding(embeddingBaseUrl, "embedding-secret", "embed", 3),
                new AppProperties.Chat(chatBaseUrl, "chat-secret", "chat", "none"),
                new AppProperties.Retrieval(Duration.ofSeconds(1), readTimeout,
                        1000, 10, 20, 0.35, 0.55, 0.45, 60, false));
    }

    private OpenAiEmbeddingGateway embeddingGateway(String path, Duration readTimeout) {
        AppProperties properties = properties(baseUrl + path, baseUrl + "/v1", readTimeout);
        return new OpenAiEmbeddingGateway(properties, new GatewayRestClientFactory(properties));
    }

    private OpenAiChatGateway chatGateway(String path) {
        return chatGateway(path, Duration.ofSeconds(2));
    }

    private OpenAiChatGateway chatGateway(String path, Duration readTimeout) {
        AppProperties properties = properties(baseUrl + "/v1", baseUrl + path, readTimeout);
        return new OpenAiChatGateway(properties, new GatewayRestClientFactory(properties));
    }

    private void assertUnavailable(OpenAiEmbeddingGateway gateway) {
        assertThatThrownBy(() -> gateway.embed(List.of("不得出现在错误中的完整正文")))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Embedding service is temporarily unavailable")
                .hasMessageNotContaining("provider leaked body")
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining("embedding-secret")
                .hasMessageNotContaining("完整正文");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sse(HttpExchange exchange, String body, int chunkSize) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
        exchange.sendResponseHeaders(200, 0);
        for (int offset = 0; offset < bytes.length; offset += chunkSize) {
            int size = Math.min(chunkSize, bytes.length - offset);
            exchange.getResponseBody().write(bytes, offset, size);
            exchange.getResponseBody().flush();
        }
        exchange.close();
    }
}
