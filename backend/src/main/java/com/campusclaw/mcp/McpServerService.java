package com.campusclaw.mcp;

import com.campusclaw.common.BadGatewayException;
import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.ConflictException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.config.McpProperties;
import com.campusclaw.persistence.McpServer;
import com.campusclaw.persistence.McpServerRepository;
import com.campusclaw.persistence.McpTool;
import com.campusclaw.persistence.McpToolCallRepository;
import com.campusclaw.persistence.McpToolRepository;
import com.campusclaw.persistence.UserAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理班级 MCP 配置，并把慢网络探测与短数据库事务分离。 */
@Service
public class McpServerService {
    private final McpServerRepository servers;
    private final McpToolRepository tools;
    private final McpToolCallRepository calls;
    private final McpTokenCipher tokens;
    private final McpHttpClient client;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Set<String> allowedHosts;

    public McpServerService(McpServerRepository servers, McpToolRepository tools,
                            McpToolCallRepository calls, McpTokenCipher tokens,
                            McpHttpClient client, ObjectMapper objectMapper,
                            TransactionTemplate transactions, McpProperties properties) {
        this.servers = servers;
        this.tools = tools;
        this.calls = calls;
        this.tokens = tokens;
        this.client = client;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.allowedHosts = new HashSet<>(properties.allowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).toList());
    }

    @Transactional(readOnly = true)
    public List<McpServerView> list(UserAccount teacher) {
        return servers.findAllByClassIdOrderByCreatedAtAsc(teacher.getClassId()).stream()
                .map(server -> McpServerView.from(server, tools.countByServerId(server.getId())))
                .toList();
    }

    @Transactional
    public McpServerView create(McpCreateServerRequest request, UserAccount teacher) {
        String name = validateName(request.name());
        String url = normalizeUrl(request.baseUrl());
        if (servers.findFirstByClassIdAndName(teacher.getClassId(), name).isPresent()) {
            throw new BadRequestException("本班已存在同名 MCP 服务");
        }
        McpServer server = new McpServer(teacher.getClassId(), name, url, tokens.encrypt(request.token()));
        try {
            return McpServerView.from(servers.saveAndFlush(server), 0);
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("本班已存在同名 MCP 服务", exception);
        }
    }

    @Transactional
    public McpServerView update(Long id, McpUpdateServerRequest request, UserAccount teacher) {
        McpServer server = requireServer(id, teacher.getClassId());
        String name = validateName(request.name());
        if (servers.existsByClassIdAndNameAndIdNot(teacher.getClassId(), name, id)) {
            throw new BadRequestException("本班已存在同名 MCP 服务");
        }
        String ciphertext = request.token() == null || request.token().isBlank()
                ? server.getAuthTokenCiphertext() : tokens.encrypt(request.token().trim());
        server.updateConfiguration(name, normalizeUrl(request.baseUrl()), ciphertext);
        tools.deleteAllByServerId(id);
        try {
            return McpServerView.from(servers.saveAndFlush(server), 0);
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("本班已存在同名 MCP 服务", exception);
        }
    }

    public McpServerView connect(Long id, UserAccount teacher) {
        ConnectionSnapshot snapshot = transactions.execute(status -> {
            McpServer server = requireServer(id, teacher.getClassId());
            return new ConnectionSnapshot(server.getId(), server.getClassId(), server.getBaseUrl(),
                    tokens.decrypt(server.getAuthTokenCiphertext()), server.getConfigVersion());
        });
        List<McpHttpClient.DiscoveredTool> discovered;
        try {
            discovered = client.discover(snapshot.baseUrl(), snapshot.token());
        } catch (McpRemoteException exception) {
            disconnectAfterFailure(snapshot);
            throw new BadGatewayException("MCP 服务连接失败");
        }
        return transactions.execute(status -> {
            McpServer server = requireServer(snapshot.id(), snapshot.classId());
            if (server.getConfigVersion() != snapshot.version()) {
                throw new ConflictException("MCP 服务配置已变化，请重新连接");
            }
            tools.deleteAllByServerId(server.getId());
            discovered.forEach(tool -> tools.save(new McpTool(server.getId(), tool.name(), tool.description(),
                    writeJson(tool.inputSchema()))));
            tools.flush();
            server.markConnected();
            servers.saveAndFlush(server);
            return McpServerView.from(server, discovered.size());
        });
    }

    @Transactional
    public McpServerView disconnect(Long id, UserAccount teacher) {
        McpServer server = requireServer(id, teacher.getClassId());
        server.disconnectAndInvalidate();
        tools.deleteAllByServerId(id);
        tools.flush();
        servers.saveAndFlush(server);
        return McpServerView.from(server, 0);
    }

    @Transactional(readOnly = true)
    public List<McpToolView> listTools(Long id, UserAccount teacher) {
        requireServer(id, teacher.getClassId());
        return tools.findAllByServerIdOrderByIdAsc(id).stream().map(tool -> new McpToolView(
                tool.getId(), tool.getName(), tool.getDescription(), readJson(tool.getInputSchema()))).toList();
    }

    @Transactional(readOnly = true)
    public List<McpToolCallView> listCalls(int limit, UserAccount teacher) {
        if (limit < 1 || limit > 100) throw new BadRequestException("limit 必须在 1 到 100 之间");
        return calls.findAllByClassIdOrderByCreatedAtDesc(teacher.getClassId(), PageRequest.of(0, limit)).stream()
                .map(McpToolCallView::from).toList();
    }

    @Transactional
    public void delete(Long id, UserAccount teacher) {
        McpServer server = requireServer(id, teacher.getClassId());
        if (calls.existsByServerId(id)) {
            throw new ConflictException("服务已有调用审计，请改为断开服务");
        }
        try {
            servers.delete(server);
            servers.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("服务已有调用审计，请改为断开服务");
        }
    }

    private void disconnectAfterFailure(ConnectionSnapshot snapshot) {
        transactions.executeWithoutResult(status -> servers.findByIdAndClassId(snapshot.id(), snapshot.classId())
                .filter(server -> server.getConfigVersion() == snapshot.version())
                .ifPresent(server -> {
                    server.markDisconnected();
                    tools.deleteAllByServerId(server.getId());
                    servers.saveAndFlush(server);
                }));
    }

    private McpServer requireServer(Long id, Long classId) {
        return servers.findByIdAndClassId(id, classId).orElseThrow(NotFoundException::new);
    }

    private String validateName(String value) {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new BadRequestException("MCP 服务名格式不合法");
        }
        return clean;
    }

    private String normalizeUrl(String value) {
        try {
            URI input = new URI(value == null ? "" : value.trim());
            String scheme = input.getScheme() == null ? "" : input.getScheme().toLowerCase(Locale.ROOT);
            String host = input.getHost() == null ? "" : input.getHost().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || host.isBlank()
                    || input.getUserInfo() != null || input.getQuery() != null || input.getFragment() != null
                    || !allowedHosts.contains(host)) {
                throw new BadRequestException("MCP Base URL 不合法或目标主机未被允许");
            }
            String path = input.getPath() == null ? "" : input.getPath().replaceAll("/+$", "");
            if (!path.endsWith("/mcp")) path = path + "/mcp";
            return new URI(scheme, null, host, input.getPort(), path, null, null).toString();
        } catch (URISyntaxException exception) {
            throw new BadRequestException("MCP Base URL 不合法", exception);
        }
    }

    private String writeJson(JsonNode value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Invalid MCP schema", exception); }
    }

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored MCP schema is invalid", exception); }
    }

    private record ConnectionSnapshot(Long id, Long classId, String baseUrl, String token, long version) { }
}
