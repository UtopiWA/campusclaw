package com.campusclaw.gateway;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 仅接受服务端已构造的消息，调用非流式 OpenAI-compatible chat API。 */
@Component
public class OpenAiChatGateway implements ChatGateway {
    private final RestClient client;
    private final GatewayRestClientFactory requests;
    private final String model;

    public OpenAiChatGateway(AppProperties properties, GatewayRestClientFactory requests) {
        this.requests = requests;
        this.model = properties.chat().model();
        this.client = requests.create(properties.chat().baseUrl(), properties.chat().apiKey());
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
}
