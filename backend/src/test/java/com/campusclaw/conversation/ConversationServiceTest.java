package com.campusclaw.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.persistence.Assistant;
import com.campusclaw.persistence.AssistantRepository;
import com.campusclaw.persistence.Conversation;
import com.campusclaw.persistence.ConversationMessage;
import com.campusclaw.persistence.ConversationMessageRepository;
import com.campusclaw.persistence.ConversationRepository;
import com.campusclaw.persistence.MessageRole;
import com.campusclaw.persistence.MessageStatus;
import com.campusclaw.persistence.UserAccount;
import com.campusclaw.mcp.ToolCallSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConversationServiceTest {
    private ConversationRepository conversations;
    private ConversationMessageRepository messages;
    private AssistantRepository assistants;
    private ConversationService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(ConversationMessageRepository.class);
        assistants = mock(AssistantRepository.class);
        service = new ConversationService(
                conversations, messages, assistants, new ObjectMapper(), new ConversationCitationNormalizer());
        user = mock(UserAccount.class);
        when(user.getId()).thenReturn(4L);
        when(user.getClassId()).thenReturn(7L);
    }

    @Test
    void createsOnlyWithEnabledSameClassAssistant() {
        Assistant assistant = mock(Assistant.class);
        Conversation saved = mock(Conversation.class);
        when(assistant.getId()).thenReturn(3L);
        when(assistant.getName()).thenReturn("阅读助手");
        when(assistants.findByIdAndClassIdAndEnabledTrue(3L, 7L)).thenReturn(Optional.of(assistant));
        when(conversations.saveAndFlush(any())).thenReturn(saved);
        when(saved.getAssistantId()).thenReturn(3L);

        ConversationView result = service.create(3L, user);

        assertThat(result.assistantName()).isEqualTo("阅读助手");
        assertThat(result.messages()).isEmpty();
        verify(conversations).saveAndFlush(any(Conversation.class));

        when(assistants.findByIdAndClassIdAndEnabledTrue(9L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(9L, user)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void readsOnlyOwnedConversationAndRestoresCitationSnapshot() {
        Conversation conversation = mock(Conversation.class);
        Assistant assistant = mock(Assistant.class);
        ConversationMessage message = mock(ConversationMessage.class);
        when(conversation.getAssistantId()).thenReturn(3L);
        when(assistant.getName()).thenReturn("阅读助手");
        when(messages.findAllByConversationIdOrderByIdAsc(8L)).thenReturn(List.of(message));
        when(message.getRole()).thenReturn(MessageRole.ASSISTANT);
        when(message.getStatus()).thenReturn(MessageStatus.COMPLETED);
        when(message.getContent()).thenReturn("回答[4]");
        when(message.getCitationsJson()).thenReturn("""
                [{"number":4,"materialId":2,"materialTitle":"讲义","chunkId":5,
                  "chunkIndex":0,"excerpt":"片段"}]
                """);
        when(message.getToolCallsJson()).thenReturn("""
                [{"ref":"formula_hint@demo","status":"ok","detail":"使用距离公式"}]
                """);
        when(conversations.findByIdAndUserIdAndClassId(8L, 4L, 7L)).thenReturn(Optional.of(conversation));
        when(assistants.findByIdAndClassId(3L, 7L)).thenReturn(Optional.of(assistant));

        ConversationView result = service.get(8L, user);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).content()).isEqualTo("回答[1]");
        assertThat(result.messages().get(0).citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.number()).isEqualTo(1);
                    assertThat(citation.materialTitle()).isEqualTo("讲义");
                });
        assertThat(result.messages().get(0).toolCalls()).containsExactly(
                new ToolCallSummary("formula_hint@demo", "ok", "使用距离公式"));

        when(conversations.findByIdAndUserIdAndClassId(99L, 4L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(99L, user)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void firstValidQuestionIsSavedAndUpdatesUnicodeTitle() {
        Conversation conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(8L);
        when(conversation.getTitle()).thenReturn(ConversationService.INITIAL_TITLE);
        when(conversations.findByIdAndUserIdAndClassId(8L, 4L, 7L)).thenReturn(Optional.of(conversation));
        when(messages.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String question = "😀".repeat(10) + "这是一段超过二十四个字符的问题内容";

        ConversationMessage result = service.saveUserMessage(8L, "  " + question + "  ", user);

        assertThat(result.getContent()).isEqualTo(question);
        verify(conversation).updateTitle(question.substring(0, question.offsetByCodePoints(0, 24)));
        verify(messages).saveAndFlush(any(ConversationMessage.class));
        verify(conversations).saveAndFlush(conversation);
    }

    @Test
    void invalidQuestionDoesNotReadConversationOrWriteMessages() {
        assertThatThrownBy(() -> service.saveUserMessage(8L, "只", user))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.saveUserMessage(8L, "字".repeat(501), user))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(conversations, messages);
        verify(assistants, never()).findAllByIdInAndClassId(any(), any());
    }

    @Test
    void returnsSixCompletedHistoryMessagesInChronologicalOrderAndTruncatesByCodePoint() {
        List<ConversationMessage> newestFirst = List.of(
                message(MessageRole.ASSISTANT, "末条"),
                message(MessageRole.USER, "😀".repeat(501)),
                message(MessageRole.ASSISTANT, "四"),
                message(MessageRole.USER, "三"),
                message(MessageRole.ASSISTANT, "二"),
                message(MessageRole.USER, "首条"));
        when(messages.findTop6ByConversationIdAndStatusAndIdLessThanOrderByIdDesc(
                8L, MessageStatus.COMPLETED, 90L)).thenReturn(newestFirst);

        List<HistoryMessage> result = service.historyBefore(8L, 90L);

        assertThat(result).hasSize(6);
        assertThat(result.get(0).content()).isEqualTo("首条");
        assertThat(result.get(5).content()).isEqualTo("末条");
        assertThat(result.get(4).content().codePointCount(0, result.get(4).content().length())).isEqualTo(500);
    }

    @Test
    void existingConversationUsesDisabledAssistantButNewConversationCannot() {
        Conversation conversation = mock(Conversation.class);
        Assistant disabled = mock(Assistant.class);
        when(conversation.getAssistantId()).thenReturn(3L);
        when(disabled.getName()).thenReturn("已停用助手");
        when(conversations.findByIdAndUserIdAndClassId(8L, 4L, 7L)).thenReturn(Optional.of(conversation));
        when(assistants.findByIdAndClassId(3L, 7L)).thenReturn(Optional.of(disabled));
        when(assistants.findByIdAndClassIdAndEnabledTrue(3L, 7L)).thenReturn(Optional.empty());

        assertThat(service.get(8L, user).assistantName()).isEqualTo("已停用助手");
        assertThatThrownBy(() -> service.create(3L, user)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deletesOnlyAnOwnedConversation() {
        Conversation owned = mock(Conversation.class);
        when(conversations.findByIdAndUserIdAndClassId(8L, 4L, 7L)).thenReturn(Optional.of(owned));

        service.delete(8L, user);

        verify(conversations).delete(owned);
        verify(conversations).flush();
        when(conversations.findByIdAndUserIdAndClassId(9L, 4L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(9L, user)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void storesToolCallsInTheSameAssistantMessageSnapshot() {
        Conversation conversation = mock(Conversation.class);
        when(conversations.findByIdAndUserIdAndClassId(8L, 4L, 7L)).thenReturn(Optional.of(conversation));
        when(messages.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        List<ToolCallSummary> calls = List.of(
                new ToolCallSummary("formula_hint@demo", "ok", "使用距离公式"));

        ConversationMessage saved = service.saveAssistantMessage(
                8L, 4L, 7L, "先列出已知量", "提示", List.of(), calls, MessageStatus.COMPLETED);

        assertThat(saved.getToolCallsJson())
                .contains("formula_hint@demo", "\"status\":\"ok\"", "使用距离公式");
        verify(conversation).touch();
        verify(conversations).saveAndFlush(conversation);
    }

    private ConversationMessage message(MessageRole role, String content) {
        ConversationMessage message = mock(ConversationMessage.class);
        when(message.getRole()).thenReturn(role);
        when(message.getContent()).thenReturn(content);
        return message;
    }
}
