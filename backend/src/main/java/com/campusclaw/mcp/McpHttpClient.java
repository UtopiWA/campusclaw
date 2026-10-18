package com.campusclaw.mcp;

import com.campusclaw.config.McpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 最小 JSON-RPC MCP 客户端；每个请求仅执行一次且拒绝 HTTP 重定向。 */
@Component
public class McpHttpClient {
    private final ObjectMapper objectMapper;
    private final RestClient.Builder builder;

    public McpHttpClient(ObjectMapper objectMapper, McpProperties properties) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.builder = RestClient.builder().requestFactory(factory);
    }

    public List<DiscoveredTool> discover(String endpoint, String token) {
        JsonNode initialized = rpc(endpoint, token, 1, "initialize", Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "campusclaw", "version", "0.1.0")));
        if (!"2025-03-26".equals(initialized.path("protocolVersion").asText())
                || !initialized.path("capabilities").isObject()
                || !initialized.path("serverInfo").isObject()
                || initialized.path("serverInfo").path("name").asText("").isBlank()
                || initialized.path("serverInfo").path("version").asText("").isBlank()) {
            throw new McpRemoteException("Invalid initialize result");
        }
        JsonNode result = rpc(endpoint, token, 2, "tools/list", Map.of());
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) throw new McpRemoteException("Invalid tools/list result");
        List<DiscoveredTool> discovered = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : tools) {
            String name = item.path("name").asText("");
            String description = item.path("description").asText("");
            JsonNode schema = item.path("inputSchema");
            if (!name.matches("[a-z][a-z0-9_]{0,31}") || !names.add(name) || description.isBlank()
                    || description.codePointCount(0, description.length()) > 1000
                    || !schema.isObject() || !"object".equals(schema.path("type").asText())
                    || !schema.path("properties").isObject()) {
                throw new McpRemoteException("Invalid tool definition");
            }
            discovered.add(new DiscoveredTool(name, description, schema));
        }
        return List.copyOf(discovered);
    }

    public String call(String endpoint, String token, String toolName, Map<String, Object> arguments) {
        JsonNode result = rpc(endpoint, token, 3, "tools/call", Map.of("name", toolName, "arguments", arguments));
        if (result.path("isError").asBoolean(false)) throw new McpRemoteException("Remote tool returned an error");
        JsonNode content = result.path("content");
        if (!content.isArray()) throw new McpRemoteException("Invalid tools/call result");
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText()) && item.path("text").isTextual()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(item.path("text").asText());
            }
        }
        if (text.toString().isBlank()) throw new McpRemoteException("Tool returned no text");
        return text.toString();
    }

    private JsonNode rpc(String endpoint, String token, int id, String method, Map<String, Object> params) {
        try {
            JsonNode response = builder.build().post().uri(endpoint)
                    .header("Authorization", "Bearer " + token)
                    .body(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params))
                    .retrieve().body(JsonNode.class);
            if (response == null || !"2.0".equals(response.path("jsonrpc").asText())
                    || response.path("id").asInt(Integer.MIN_VALUE) != id || response.has("error")
                    || !response.path("result").isObject()) {
                throw new McpRemoteException("Invalid JSON-RPC response");
            }
            return response.path("result");
        } catch (RestClientException exception) {
            throw new McpRemoteException("MCP request failed", exception);
        }
    }

    public record DiscoveredTool(String name, String description, JsonNode inputSchema) { }
}
