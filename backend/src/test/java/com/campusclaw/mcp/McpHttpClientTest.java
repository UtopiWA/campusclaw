package com.campusclaw.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.config.McpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpHttpClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<HttpServer> running = new ArrayList<>();

    @AfterEach
    void stopServers() {
        running.forEach(server -> server.stop(0));
    }

    @Test
    void validatesDiscoveryAndCallsAToolWithBearerAuthentication() throws Exception {
        List<String> methods = new ArrayList<>();
        HttpServer server = server(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer demo-token");
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).startsWith("application/json");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Length"))
                    .isEqualTo(String.valueOf(requestBody.length));
            assertThat(exchange.getRequestHeaders().getFirst("Transfer-Encoding")).isNull();
            JsonNode request = mapper.readTree(requestBody);
            methods.add(request.path("method").asText());
            int id = request.path("id").asInt();
            String result = switch (request.path("method").asText()) {
                case "initialize" -> "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"serverInfo\":{\"name\":\"demo\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"formula_hint\",\"description\":\"Offer a deterministic hint.\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"Use the distance formula.\"}],\"isError\":false}";
                default -> throw new AssertionError("Unexpected method");
            };
            json(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
        });
        McpHttpClient client = client();
        String endpoint = endpoint(server);

        List<McpHttpClient.DiscoveredTool> tools = client.discover(endpoint, "demo-token");
        String result = client.call(endpoint, "demo-token", "formula_hint", Map.of("query", "help"));

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("formula_hint");
            assertThat(tool.inputSchema().path("type").asText()).isEqualTo("object");
        });
        assertThat(result).isEqualTo("Use the distance formula.");
        assertThat(methods).containsExactly("initialize", "tools/list", "tools/call");
    }

    @Test
    void rejectsAnInvalidInitializeResultBeforeListingTools() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            json(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"old\"}}");
        });

        assertThatThrownBy(() -> client().discover(endpoint(server), "demo-token"))
                .isInstanceOf(McpRemoteException.class).hasMessageContaining("initialize");
        assertThat(requests).hasValue(1);
    }

    @Test
    void doesNotFollowRedirectsOrRetryTheRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Location", endpoint(exchange.getLocalAddress()) + "/mcp");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> client().discover(endpoint(server), "demo-token"))
                .isInstanceOf(McpRemoteException.class);
        assertThat(requests).hasValue(1);
    }

    private McpHttpClient client() {
        return new McpHttpClient(mapper, new McpProperties(
                "unused", List.of("localhost"), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    private HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable failure) {
                exchange.close();
                if (failure instanceof IOException io) throw io;
                throw new IOException(failure);
            }
        });
        server.start();
        running.add(server);
        return server;
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String endpoint(HttpServer server) {
        return endpoint(server.getAddress()) + "/mcp";
    }

    private String endpoint(InetSocketAddress address) {
        return "http://127.0.0.1:" + address.getPort();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
