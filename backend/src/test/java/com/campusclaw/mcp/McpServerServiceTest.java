package com.campusclaw.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.common.BadGatewayException;
import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.ConflictException;
import com.campusclaw.config.McpProperties;
import com.campusclaw.persistence.McpServer;
import com.campusclaw.persistence.McpServerRepository;
import com.campusclaw.persistence.McpToolCall;
import com.campusclaw.persistence.McpToolCallRepository;
import com.campusclaw.persistence.McpToolCallStatus;
import com.campusclaw.persistence.McpToolRepository;
import com.campusclaw.persistence.Role;
import com.campusclaw.persistence.UserAccount;
import com.campusclaw.persistence.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class McpServerServiceTest {
    private final McpServerRepository servers = mock(McpServerRepository.class);
    private final McpToolRepository tools = mock(McpToolRepository.class);
    private final McpToolCallRepository calls = mock(McpToolCallRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final McpTokenCipher tokens = mock(McpTokenCipher.class);
    private final McpHttpClient client = mock(McpHttpClient.class);
    private McpServerService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        service = new McpServerService(servers, tools, calls, users, tokens, client, new ObjectMapper(),
                new TransactionTemplate(manager),
                new McpProperties("unused", List.of("mcp-demo"), Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    @Test
    void createsNormalizedAllowedServerWithoutExposingTheToken() {
        when(tokens.encrypt("secret")).thenReturn("v1:ciphertext");
        when(servers.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        McpServerView view = service.create(
                new McpCreateServerRequest("demo", "HTTP://MCP-DEMO:8090/", "secret"), teacher());

        assertThat(view.baseUrl()).isEqualTo("http://mcp-demo:8090/mcp");
        assertThat(view.hasAuth()).isTrue();
        assertThat(view.connected()).isFalse();
        verify(tokens).encrypt("secret");
    }

    @Test
    void rejectsHostsOutsideTheExplicitAllowList() {
        assertThatThrownBy(() -> service.create(
                new McpCreateServerRequest("demo", "http://example.com", "secret"), teacher()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("未被允许");
    }

    @Test
    void discoversAndAtomicallyReplacesTheToolCache() throws Exception {
        McpServer server = server();
        when(servers.findByIdAndClassId(12L, 7L)).thenReturn(Optional.of(server));
        when(tokens.decrypt("v1:ciphertext")).thenReturn("secret");
        ObjectMapper mapper = new ObjectMapper();
        when(client.discover("http://mcp-demo:8090/mcp", "secret")).thenReturn(List.of(
                new McpHttpClient.DiscoveredTool("formula_hint", "hint",
                        mapper.readTree("{\"type\":\"object\",\"properties\":{}}"))));

        McpServerView view = service.connect(12L, teacher());

        assertThat(view.connected()).isTrue();
        assertThat(view.toolCount()).isEqualTo(1);
        InOrder cacheUpdate = inOrder(tools);
        cacheUpdate.verify(tools).deleteAllByServerId(12L);
        cacheUpdate.verify(tools).flush();
        cacheUpdate.verify(tools).save(any());
        cacheUpdate.verify(tools).flush();
        verify(servers).saveAndFlush(server);
    }

    @Test
    void failedDiscoveryDisconnectsAndClearsStaleTools() {
        McpServer server = server();
        server.markConnected();
        when(servers.findByIdAndClassId(12L, 7L)).thenReturn(Optional.of(server));
        when(tokens.decrypt("v1:ciphertext")).thenReturn("secret");
        when(client.discover(any(), any())).thenThrow(new McpRemoteException("bad response"));

        assertThatThrownBy(() -> service.connect(12L, teacher()))
                .isInstanceOf(BadGatewayException.class);

        assertThat(server.isConnected()).isFalse();
        verify(tools).deleteAllByServerId(12L);
        verify(servers).saveAndFlush(server);
    }

    @Test
    void rejectsInvalidAuditLimitAndDeletionWhenAuditExists() {
        assertThatThrownBy(() -> service.listCalls(101, teacher()))
                .isInstanceOf(BadRequestException.class);
        McpServer server = server();
        when(servers.findByIdAndClassId(12L, 7L)).thenReturn(Optional.of(server));
        when(calls.existsByServerId(12L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(12L, teacher()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void returnsAuditUsernameAndMeasuredDuration() {
        UserAccount student = new UserAccount("student-a", "hash", Role.STUDENT, 7L);
        ReflectionTestUtils.setField(student, "id", 4L);
        McpToolCall call = new McpToolCall(7L, 12L, 4L, 19L,
                "calculator@demo", McpToolCallStatus.OK, "2 + 2 = 4", 37L);
        when(calls.findAllByClassIdOrderByCreatedAtDesc(eq(7L), any())).thenReturn(List.of(call));
        when(users.findAllById(any())).thenReturn(List.of(student));

        McpToolCallView view = service.listCalls(50, teacher()).get(0);

        assertThat(view.userName()).isEqualTo("student-a");
        assertThat(view.durationMs()).isEqualTo(37L);
    }

    private McpServer server() {
        McpServer server = new McpServer(7L, "demo", "http://mcp-demo:8090/mcp", "v1:ciphertext");
        ReflectionTestUtils.setField(server, "id", 12L);
        return server;
    }

    private UserAccount teacher() {
        return new UserAccount("teacher", "hash", Role.TEACHER, 7L);
    }
}
