package com.campusclaw.gateway;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 仅接受服务端已构造的消息，调用非流式或 SSE OpenAI-compatible Chat API。 */
@Component
public class OpenAiChatGateway implements ChatGateway {
    private final RestClient client;
    private final GatewayRestClientFactory requests;
    private final String model;
    private final String streamUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final java.time.Duration readTimeout;

    public OpenAiChatGateway(AppProperties properties, GatewayRestClientFactory requests) {
        this(properties, requests, new ObjectMapper());
    }

    @Autowired
    public OpenAiChatGateway(AppProperties properties, GatewayRestClientFactory requests, ObjectMapper objectMapper) {
        this.requests = requests;
        this.model = properties.chat().model();
        this.client = requests.create(properties.chat().baseUrl(), properties.chat().apiKey());
        this.streamUrl = stripTrailingSlash(properties.chat().baseUrl()) + "/chat/completions";
        this.apiKey = properties.chat().apiKey();
        this.objectMapper = objectMapper;
        this.readTimeout = properties.retrieval().readTimeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.retrieval().connectTimeout())
                .build();
    }

    @Override
    public String complete(List<ChatMessage> messages) {
        List<Map<String, String>> bodyMessages = messages.stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList();
        JsonNode response = requests.withOneRetry("Chat service", () -> client.post()
                .uri("/chat/completions")
                .body(Map.of("model", model, "stream", false, "messages", bodyMessages))
                .retrieve()
                .body(JsonNode.class));
        String answer = response == null ? "" : response.path("choices").path(0)
                .path("message").path("content").asText("").trim();
        if (answer.isBlank()) {
            throw new DependencyUnavailableException("Chat service");
        }
        return answer;
    }

    @Override
    public ChatStream openStream(List<ChatMessage> messages) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "stream", true,
                    "messages", messages.stream()
                            .map(message -> Map.of("role", message.role(), "content", message.content()))
                            .toList()));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(streamUrl))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            return openWithOneRetry(builder.build());
        } catch (RuntimeException | IOException exception) {
            throw unavailable(exception);
        }
    }

    private ChatStream openWithOneRetry(HttpRequest request) throws IOException {
        IOException first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<InputStream> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    response.body().close();
                    throw new IOException("Chat provider returned a non-success status");
                }
                return new OpenAiChatStream(response.body(), objectMapper, readTimeout);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Chat stream interrupted", exception);
            } catch (IOException exception) {
                if (first == null) {
                    first = exception;
                } else {
                    first.addSuppressed(exception);
                }
            }
        }
        throw first == null ? new IOException("Chat stream unavailable") : first;
    }

    private DependencyUnavailableException unavailable(Exception ignored) {
        return new DependencyUnavailableException("Chat service");
    }

    private String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** 在调用线程中消费供应商 SSE，任何畸形或非正常结束都映射为脱敏依赖故障。 */
    private static final class OpenAiChatStream implements ChatStream {
        private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "chat-stream-read-timeout");
            thread.setDaemon(true);
            return thread;
        });

        private final InputStream input;
        private final ObjectMapper objectMapper;
        private final java.time.Duration readTimeout;

        private OpenAiChatStream(InputStream input, ObjectMapper objectMapper, java.time.Duration readTimeout) {
            this.input = input;
            this.objectMapper = objectMapper;
            this.readTimeout = readTimeout;
        }

        @Override
        public void consume(Consumer<String> deltaConsumer) {
            boolean done = false;
            AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                armReadTimeout(timeout);
                while ((line = reader.readLine()) != null) {
                    armReadTimeout(timeout);
                    if (line.isEmpty()) {
                        done = consumeFrame(data, deltaConsumer) || done;
                        data.setLength(0);
                        if (done) return;
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    }
                }
                if (!data.isEmpty()) {
                    done = consumeFrame(data, deltaConsumer) || done;
                }
                if (!done) {
                    throw new IOException("Chat stream ended without done marker");
                }
            } catch (IOException | RuntimeException exception) {
                throw new DependencyUnavailableException("Chat service");
            } finally {
                ScheduledFuture<?> pending = timeout.getAndSet(null);
                if (pending != null) pending.cancel(false);
            }
        }

        /** 每收到一行便重置看门狗，避免上游在流中途静默占用线程。 */
        private void armReadTimeout(AtomicReference<ScheduledFuture<?>> reference) {
            ScheduledFuture<?> next = TIMEOUTS.schedule(() -> {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // 关闭只用于唤醒阻塞读取，统一由 consume 映射为脱敏依赖错误。
                }
            }, readTimeout.toMillis(), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = reference.getAndSet(next);
            if (previous != null) previous.cancel(false);
        }

        private boolean consumeFrame(StringBuilder data, Consumer<String> deltaConsumer) throws IOException {
            if (data.isEmpty()) return false;
            String payload = data.toString();
            if ("[DONE]".equals(payload)) return true;
            JsonNode event = objectMapper.readTree(payload);
            JsonNode content = event.path("choices").path(0).path("delta").path("content");
            if (!content.isMissingNode() && !content.isNull()) {
                String value = content.asText();
                if (!value.isEmpty()) deltaConsumer.accept(value);
            }
            return false;
        }

        @Override
        public void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // 关闭失败不覆盖本轮已有的业务异常。
            }
        }
    }
}
