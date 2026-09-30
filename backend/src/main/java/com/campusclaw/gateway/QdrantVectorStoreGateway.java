package com.campusclaw.gateway;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

/** 管理 Qdrant collection 与纯编号 payload；正文始终留在 MySQL。 */
@Component
public class QdrantVectorStoreGateway implements VectorStoreGateway {
    private final RestClient client;
    private final GatewayRestClientFactory requests;
    private final String collection;
    private final int dimension;

    public QdrantVectorStoreGateway(AppProperties properties, GatewayRestClientFactory requests) {
        this.requests = requests;
        this.collection = properties.qdrant().collection();
        this.dimension = properties.embedding().dimension();
        this.client = requests.create(properties.qdrant().url(), null);
    }

    @Override
    public void ensureCollection() {
        try {
            JsonNode response = client.get().uri("/collections/{name}", collection)
                    .retrieve().body(JsonNode.class);
            JsonNode vectors = response == null ? null : response.path("result").path("config")
                    .path("params").path("vectors");
            if (vectors == null || vectors.path("size").asInt(-1) != dimension
                    || !"Cosine".equalsIgnoreCase(vectors.path("distance").asText())) {
                throw new IllegalStateException("Qdrant collection configuration is incompatible");
            }
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw new DependencyUnavailableException("Vector store");
            }
            requests.withOneRetry("Vector store", () -> client.put()
                    .uri("/collections/{name}", collection)
                    .body(Map.of("vectors", Map.of("size", dimension, "distance", "Cosine")))
                    .retrieve().toBodilessEntity());
        } catch (RestClientException exception) {
            throw new DependencyUnavailableException("Vector store");
        }
    }

    @Override
    public void upsert(List<VectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> payload = points.stream().map(point -> Map.<String, Object>of(
                "id", point.id(),
                "vector", point.vector(),
                "payload", Map.of(
                        "chunk_id", point.id(),
                        "class_id", point.classId(),
                        "material_id", point.materialId(),
                        "knowledge_entry_id", point.knowledgeEntryId(),
                        "chunk_index", point.chunkIndex())))
                .toList();
        requests.withOneRetry("Vector store", () -> client.put()
                .uri("/collections/{name}/points?wait=true", collection)
                .body(Map.of("points", payload))
                .retrieve().toBodilessEntity());
    }

    @Override
    public List<VectorMatch> search(List<Double> vector, Long classId, int limit, double threshold) {
        Map<String, Object> filter = Map.of("must", List.of(Map.of(
                "key", "class_id", "match", Map.of("value", classId))));
        JsonNode response = requests.withOneRetry("Vector store", () -> client.post()
                .uri("/collections/{name}/points/search", collection)
                .body(Map.of("vector", vector, "limit", limit, "score_threshold", threshold,
                        "with_payload", true, "with_vector", false, "filter", filter))
                .retrieve().body(JsonNode.class));
        if (response == null || !response.path("result").isArray()) {
            throw new DependencyUnavailableException("Vector store");
        }
        List<VectorMatch> matches = new ArrayList<>();
        for (JsonNode row : response.path("result")) {
            long pointId = row.path("id").asLong(-1);
            long payloadId = row.path("payload").path("chunk_id").asLong(-2);
            long payloadClass = row.path("payload").path("class_id").asLong(-1);
            double score = row.path("score").asDouble(-1);
            // 丢弃伪造或不完整 payload，随后检索服务还会执行 READY 班级回表。
            if (pointId > 0 && pointId == payloadId && payloadClass == classId && score >= threshold) {
                matches.add(new VectorMatch(pointId, score));
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public void deletePoints(List<Long> pointIds) {
        if (pointIds.isEmpty()) {
            return;
        }
        requests.withOneRetry("Vector store", () -> client.post()
                .uri("/collections/{name}/points/delete?wait=true", collection)
                .body(Map.of("points", pointIds))
                .retrieve().toBodilessEntity());
    }

    @Override
    public void deleteMaterial(Long materialId) {
        requests.withOneRetry("Vector store", () -> client.post()
                .uri("/collections/{name}/points/delete?wait=true", collection)
                .body(Map.of("filter", Map.of("must", List.of(Map.of(
                        "key", "material_id", "match", Map.of("value", materialId))))))
                .retrieve().toBodilessEntity());
    }
}
