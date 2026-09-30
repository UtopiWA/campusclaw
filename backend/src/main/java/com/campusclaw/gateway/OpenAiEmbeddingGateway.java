package com.campusclaw.gateway;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 调用 OpenAI-compatible embeddings API，并在边界校验数量和维度。 */
@Component
public class OpenAiEmbeddingGateway implements EmbeddingGateway {
    private final RestClient client;
    private final GatewayRestClientFactory requests;
    private final AppProperties.Embedding properties;

    public OpenAiEmbeddingGateway(AppProperties appProperties, GatewayRestClientFactory requests) {
        this.properties = appProperties.embedding();
        this.requests = requests;
        this.client = requests.create(properties.baseUrl(), properties.apiKey());
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        JsonNode response = requests.withOneRetry("Embedding service", () -> client.post()
                .uri("/embeddings")
                .body(Map.of("model", properties.model(), "input", texts))
                .retrieve()
                .body(JsonNode.class));
        if (response == null || !response.path("data").isArray()) {
            throw new DependencyUnavailableException("Embedding service");
        }
        List<JsonNode> rows = new ArrayList<>();
        response.path("data").forEach(rows::add);
        rows.sort(Comparator.comparingInt(row -> row.path("index").asInt()));
        if (rows.size() != texts.size()) {
            throw new DependencyUnavailableException("Embedding service");
        }
        List<List<Double>> result = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode values = row.path("embedding");
            if (!values.isArray() || values.size() != properties.dimension()) {
                throw new DependencyUnavailableException("Embedding service");
            }
            List<Double> vector = new ArrayList<>(values.size());
            values.forEach(value -> vector.add(value.asDouble()));
            result.add(List.copyOf(vector));
        }
        return List.copyOf(result);
    }
}
