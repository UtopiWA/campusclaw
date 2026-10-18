package com.campusclaw.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.agent.SkillRouter;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.gateway.ChatGateway;
import com.campusclaw.mcp.McpToolOrchestrator;
import com.campusclaw.mcp.McpTurnResult;
import com.campusclaw.mcp.ToolCallSummary;
import com.campusclaw.persistence.Assistant;
import com.campusclaw.persistence.AssistantMaterial;
import com.campusclaw.persistence.AssistantMaterialRepository;
import com.campusclaw.persistence.AssistantRepository;
import com.campusclaw.persistence.Conversation;
import com.campusclaw.persistence.ConversationMessage;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.UserAccount;
import com.campusclaw.retrieval.RetrievalService;
import com.campusclaw.retrieval.SearchMode;
import com.campusclaw.retrieval.SearchResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AgentConversationServiceTest {
    private ConversationService conversations;
    private AssistantRepository assistants;
    private AssistantMaterialRepository materials;
    private SkillRouter router;
    private RetrievalService retrieval;
    private AgentPromptBuilder prompts;
    private ChatGateway chat;
    private McpToolOrchestrator mcpTools;
    private AgentConversationService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        assistants = mock(AssistantRepository.class);
        materials = mock(AssistantMaterialRepository.class);
        router = mock(SkillRouter.class);
        retrieval = mock(RetrievalService.class);
        prompts = mock(AgentPromptBuilder.class);
        chat = mock(ChatGateway.class);
        mcpTools = mock(McpToolOrchestrator.class);
        service = new AgentConversationService(
                conversations, assistants, materials, router, retrieval, prompts, chat, mcpTools);
        user = mock(UserAccount.class);
        when(user.getId()).thenReturn(6L);
        when(user.getClassId()).thenReturn(2L);
        when(mcpTools.invoke(any(), any(), any(), any())).thenReturn(McpTurnResult.empty());
    }

    @Test
    void preparesImmutableTurnInRequiredOrderAndOpensUpstreamBeforeReturning() {
        Conversation conversation = mock(Conversation.class);
        Assistant assistant = mock(Assistant.class);
        Skill skill = mock(Skill.class);
        ConversationMessage userMessage = mock(ConversationMessage.class);
        ChatGateway.ChatStream stream = mock(ChatGateway.ChatStream.class);
        AgentTurnPlan plan = new AgentTurnPlan("解题引导", List.of(citation()),
                List.of(new ChatGateway.ChatMessage("system", "规则")));
        when(conversation.getAssistantId()).thenReturn(3L);
        when(assistant.getId()).thenReturn(3L);
        when(conversations.requireOwned(9L, user)).thenReturn(conversation);
        when(assistants.findByIdAndClassId(3L, 2L)).thenReturn(Optional.of(assistant));
        when(router.route(3L, 2L, "方程问题")).thenReturn(Optional.of(skill));
        when(materials.findAllByAssistantId(3L)).thenReturn(List.of(new AssistantMaterial(3L, 11L)));
        when(conversations.saveUserMessage(9L, "方程问题", user)).thenReturn(userMessage);
        when(userMessage.getId()).thenReturn(20L);
        SearchResponse response = new SearchResponse(SearchMode.HYBRID, null, List.of());
        when(retrieval.searchBound("方程问题", 2L, java.util.Set.of(11L), 4)).thenReturn(response);
        when(conversations.historyBefore(9L, 20L)).thenReturn(List.of());
        when(prompts.build(assistant, Optional.of(skill), List.of(), McpTurnResult.empty(), List.of(), "方程问题"))
                .thenReturn(plan);
        when(chat.openStream(plan.messages())).thenReturn(stream);

        AgentTurnPreparation result = service.prepare(9L, "方程问题", user);

        assertThat(result.stream()).isSameAs(stream);
        assertThat(result.plan()).isSameAs(plan);
        InOrder order = inOrder(conversations, router, retrieval, prompts, chat);
        order.verify(conversations).requireOwned(9L, user);
        order.verify(router).route(3L, 2L, "方程问题");
        order.verify(conversations).saveUserMessage(9L, "方程问题", user);
        order.verify(retrieval).searchBound("方程问题", 2L, java.util.Set.of(11L), 4);
        order.verify(conversations).historyBefore(9L, 20L);
        order.verify(prompts).build(assistant, Optional.of(skill), List.of(), McpTurnResult.empty(), List.of(), "方程问题");
        order.verify(chat).openStream(plan.messages());
    }

    @Test
    void stopsBeforeConfigurationAndGatewayWhenConversationIsNotOwned() {
        when(conversations.requireOwned(99L, user)).thenThrow(new NotFoundException());

        assertThatThrownBy(() -> service.prepare(99L, "问题内容", user))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(assistants, materials, router, retrieval, prompts, chat);
    }

    @Test
    void rereadsBindingsOnEveryTurnAndKeepsSavedUserMessageWhenRetrievalFails() {
        Conversation conversation = mock(Conversation.class);
        Assistant assistant = mock(Assistant.class);
        ConversationMessage firstMessage = mock(ConversationMessage.class);
        ConversationMessage secondMessage = mock(ConversationMessage.class);
        when(conversation.getAssistantId()).thenReturn(3L);
        when(assistant.getId()).thenReturn(3L);
        when(conversations.requireOwned(9L, user)).thenReturn(conversation);
        when(assistants.findByIdAndClassId(3L, 2L)).thenReturn(Optional.of(assistant));
        when(router.route(3L, 2L, "第一问")).thenReturn(Optional.empty());
        when(router.route(3L, 2L, "第二问")).thenReturn(Optional.empty());
        when(materials.findAllByAssistantId(3L)).thenReturn(
                List.of(new AssistantMaterial(3L, 11L)),
                List.of(new AssistantMaterial(3L, 12L)));
        when(conversations.saveUserMessage(9L, "第一问", user)).thenReturn(firstMessage);
        when(conversations.saveUserMessage(9L, "第二问", user)).thenReturn(secondMessage);
        when(firstMessage.getId()).thenReturn(20L);
        when(secondMessage.getId()).thenReturn(21L);
        SearchResponse response = new SearchResponse(SearchMode.HYBRID, null, List.of());
        when(retrieval.searchBound("第一问", 2L, java.util.Set.of(11L), 4)).thenReturn(response);
        when(conversations.historyBefore(9L, 20L)).thenReturn(List.of());
        AgentTurnPlan plan = new AgentTurnPlan(null, List.of(citation()), List.of());
        when(prompts.build(assistant, Optional.empty(), List.of(), McpTurnResult.empty(), List.of(), "第一问"))
                .thenReturn(plan);
        when(chat.openStream(plan.messages())).thenReturn(mock(ChatGateway.ChatStream.class));

        service.prepare(9L, "第一问", user);
        when(retrieval.searchBound("第二问", 2L, java.util.Set.of(12L), 4))
                .thenThrow(new DependencyUnavailableException("Vector store"));

        assertThatThrownBy(() -> service.prepare(9L, "第二问", user))
                .isInstanceOf(DependencyUnavailableException.class);

        verify(materials, org.mockito.Mockito.times(2)).findAllByAssistantId(3L);
        verify(conversations).saveUserMessage(9L, "第二问", user);
        verify(retrieval).searchBound("第二问", 2L, java.util.Set.of(12L), 4);
        verify(chat, org.mockito.Mockito.times(1)).openStream(any());
    }

    @Test
    void returnsFixedNoEvidenceStreamWithoutCallingChatGateway() {
        Conversation conversation = mock(Conversation.class);
        Assistant assistant = mock(Assistant.class);
        ConversationMessage userMessage = mock(ConversationMessage.class);
        when(conversation.getAssistantId()).thenReturn(3L);
        when(assistant.getId()).thenReturn(3L);
        when(conversations.requireOwned(9L, user)).thenReturn(conversation);
        when(assistants.findByIdAndClassId(3L, 2L)).thenReturn(Optional.of(assistant));
        when(router.route(3L, 2L, "冷门问题")).thenReturn(Optional.empty());
        when(materials.findAllByAssistantId(3L)).thenReturn(List.of(new AssistantMaterial(3L, 11L)));
        when(conversations.saveUserMessage(9L, "冷门问题", user)).thenReturn(userMessage);
        when(userMessage.getId()).thenReturn(20L);
        when(retrieval.searchBound("冷门问题", 2L, java.util.Set.of(11L), 4))
                .thenReturn(new SearchResponse(SearchMode.HYBRID, RetrievalService.NO_EVIDENCE, List.of()));
        when(conversations.historyBefore(9L, 20L)).thenReturn(List.of());
        AgentTurnPlan plan = new AgentTurnPlan(null, List.of(), List.of());
        when(prompts.build(assistant, Optional.empty(), List.of(), McpTurnResult.empty(), List.of(), "冷门问题"))
                .thenReturn(plan);

        AgentTurnPreparation result = service.prepare(9L, "冷门问题", user);
        StringBuilder output = new StringBuilder();
        result.stream().consume(output::append);

        assertThat(output).hasToString(RetrievalService.NO_EVIDENCE);
        verifyNoInteractions(chat);
    }

    @Test
    void opensChatForUsableToolEvidenceEvenWhenMaterialEvidenceIsEmpty() {
        Conversation conversation = mock(Conversation.class);
        Assistant assistant = mock(Assistant.class);
        Skill skill = mock(Skill.class);
        ConversationMessage userMessage = mock(ConversationMessage.class);
        ChatGateway.ChatStream stream = mock(ChatGateway.ChatStream.class);
        McpTurnResult toolResult = new McpTurnResult(
                List.of(new ToolCallSummary("formula_hint@demo", "ok", "使用距离公式")),
                List.of(new McpTurnResult.ToolContext("formula_hint@demo", "使用距离公式")));
        AgentTurnPlan plan = new AgentTurnPlan("提示", List.of(), toolResult.calls(),
                List.of(new ChatGateway.ChatMessage("system", "规则")));
        when(conversation.getAssistantId()).thenReturn(3L);
        when(assistant.getId()).thenReturn(3L);
        when(conversations.requireOwned(9L, user)).thenReturn(conversation);
        when(assistants.findByIdAndClassId(3L, 2L)).thenReturn(Optional.of(assistant));
        when(router.route(3L, 2L, "提示我下一步")).thenReturn(Optional.of(skill));
        when(materials.findAllByAssistantId(3L)).thenReturn(List.of());
        when(conversations.saveUserMessage(9L, "提示我下一步", user)).thenReturn(userMessage);
        when(userMessage.getId()).thenReturn(20L);
        when(retrieval.searchBound("提示我下一步", 2L, java.util.Set.of(), 4))
                .thenReturn(new SearchResponse(SearchMode.HYBRID, RetrievalService.NO_EVIDENCE, List.of()));
        when(mcpTools.invoke(Optional.of(skill), "提示我下一步", 9L, user)).thenReturn(toolResult);
        when(conversations.historyBefore(9L, 20L)).thenReturn(List.of());
        when(prompts.build(assistant, Optional.of(skill), List.of(), toolResult, List.of(), "提示我下一步"))
                .thenReturn(plan);
        when(chat.openStream(plan.messages())).thenReturn(stream);

        AgentTurnPreparation result = service.prepare(9L, "提示我下一步", user);

        assertThat(result.stream()).isSameAs(stream);
        verify(chat).openStream(plan.messages());
    }

    private ConversationCitation citation() {
        return new ConversationCitation(1, 11L, "讲义", 21L, 0, "可靠片段");
    }
}
