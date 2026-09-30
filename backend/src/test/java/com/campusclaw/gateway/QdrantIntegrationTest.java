package com.campusclaw.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.config.AppProperties;
import com.campusclaw.gateway.VectorStoreGateway.VectorPoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class QdrantIntegrationTest {
    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.15.4")
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/readyz").forPort(6333));

    @Test
    void createsReusesAndRejectsIncompatibleCollectionWhileRoundTrippingNumberOnlyPoints() throws Exception {
        String url = "http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333);
        AppProperties properties = properties(url, 3);
        QdrantVectorStoreGateway gateway = new QdrantVectorStoreGateway(
                properties, new GatewayRestClientFactory(properties));

        gateway.ensureCollection();
        gateway.ensureCollection();
        gateway.upsert(List.of(
                new VectorPoint(101L, List.of(1.0, 0.0, 0.0), 7L, 8L, 9L, 0),
                new VectorPoint(102L, List.of(1.0, 0.0, 0.0), 7L, 10L, 11L, 1)));

        JsonNode payload = pointPayload(url, 101L);
        List<String> payloadFields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(payloadFields::add);
        assertThat(payloadFields).containsExactlyInAnyOrder(
                "chunk_id", "class_id", "material_id", "knowledge_entry_id", "chunk_index");
        assertThat(payload.toString()).doesNotContain("chunk_text", "正文");

        assertThat(gateway.search(List.of(1.0, 0.0, 0.0), 7L, 5, 0.35))
                .extracting(VectorStoreGateway.VectorMatch::chunkId).containsExactlyInAnyOrder(101L, 102L);
        assertThat(gateway.search(List.of(1.0, 0.0, 0.0), 99L, 5, 0.35)).isEmpty();

        AppProperties incompatible = properties(url, 4);
        assertThatThrownBy(() -> new QdrantVectorStoreGateway(
                incompatible, new GatewayRestClientFactory(incompatible)).ensureCollection())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");
        assertThat(gateway.search(List.of(1.0, 0.0, 0.0), 7L, 5, 0.35)).hasSize(2);

        gateway.deletePoints(List.of(101L));
        assertThat(gateway.search(List.of(1.0, 0.0, 0.0), 7L, 5, 0.35))
                .extracting(VectorStoreGateway.VectorMatch::chunkId).containsExactly(102L);
        gateway.deleteMaterial(10L);
        assertThat(gateway.search(List.of(1.0, 0.0, 0.0), 7L, 5, 0.35)).isEmpty();
    }

    private AppProperties properties(String url, int dimension) {
        return new AppProperties("./uploads", 1024,
                new AppProperties.DemoSeed(false, ""), new AppProperties.Qdrant(url, "integration_chunks"),
                new AppProperties.Embedding("http://localhost/v1", "key", "embed", dimension),
                new AppProperties.Chat("http://localhost/v1", "key", "chat"),
                new AppProperties.Retrieval(Duration.ofSeconds(2), Duration.ofSeconds(5),
                        1000, 10, 20, 0.35, 60, false));
    }

    private JsonNode pointPayload(String url, long pointId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(url + "/collections/integration_chunks/points/" + pointId)).GET().build();
        String body = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
        return new ObjectMapper().readTree(body).path("result").path("payload");
    }
}
