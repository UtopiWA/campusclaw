package com.campusclaw.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.persistence.McpServer;
import com.campusclaw.persistence.McpServerRepository;
import com.campusclaw.persistence.McpTool;
import com.campusclaw.persistence.McpToolRepository;
import com.campusclaw.persistence.Role;
import com.campusclaw.persistence.SkillRepository;
import com.campusclaw.persistence.UserAccount;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SkillToolReferenceValidationTest {
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillTextParser parser = mock(SkillTextParser.class);
    private final McpServerRepository servers = mock(McpServerRepository.class);
    private final McpToolRepository tools = mock(McpToolRepository.class);
    private final SkillService service = new SkillService(skills, parser, servers, tools);
    private SkillDefinition definition;

    @BeforeEach
    void setUp() {
        definition = new SkillDefinition("提示", List.of("题目"), "",
                List.of("knowledge_search", "formula_hint@demo"), true, "引导", "source");
        when(parser.parse("source")).thenReturn(definition);
    }

    @Test
    void savesOrderedReferencesOnlyWhenTheSameClassConnectedCacheContainsEveryTool() {
        McpServer server = connectedServer();
        when(servers.findFirstByClassIdAndName(7L, "demo")).thenReturn(Optional.of(server));
        when(tools.findFirstByServerIdAndName(12L, "formula_hint"))
                .thenReturn(Optional.of(new McpTool(12L, "formula_hint", "hint", "{}")));
        when(skills.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SkillView saved = service.create("source", teacher());

        assertThat(saved.toolRefs()).containsExactly("knowledge_search", "formula_hint@demo");
    }

    @Test
    void rejectsDisconnectedOrMissingReferencesBeforeMutatingTheSkill() {
        McpServer disconnected = new McpServer(7L, "demo", "http://mcp-demo/mcp", "ciphertext");
        when(servers.findFirstByClassIdAndName(7L, "demo")).thenReturn(Optional.of(disconnected));

        assertThatThrownBy(() -> service.create("source", teacher()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("工具不可用");

        verifyNoInteractions(skills, tools);
    }

    private McpServer connectedServer() {
        McpServer server = new McpServer(7L, "demo", "http://mcp-demo/mcp", "ciphertext");
        ReflectionTestUtils.setField(server, "id", 12L);
        server.markConnected();
        return server;
    }

    private UserAccount teacher() {
        return new UserAccount("teacher", "hash", Role.TEACHER, 7L);
    }
}
