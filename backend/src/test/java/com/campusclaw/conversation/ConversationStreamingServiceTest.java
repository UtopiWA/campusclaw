package com.campusclaw.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.gateway.ChatGateway;
import com.campusclaw.persistence.ConversationMessage;
import com.campusclaw.persistence.MessageStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConversationStreamingServiceTest {

    @Test
    void writesMetaIncrementalDeltasAndDoneOnlyAfterCompletedMessagePersists() {
        ConversationService conversations = mock(ConversationService.class);
        ConversationMessage saved = mock(ConversationMessage.class);
        when(saved.getId()).thenReturn(77L);
        when(conversations.saveAssistantMessage(
                eq(9L), eq(6L), eq(2L), eq("第一段"), eq("阅读"), any(), eq(MessageStatus.COMPLETED)))
                .thenReturn(saved);
        ConversationStreamingService service = new ConversationStreamingService(conversations, new ObjectMapper());
        AgentTurnPreparation preparation = preparation("阅读", List.of(), consumer -> {
            consumer.accept("第一");
            consumer.accept("段");
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        service.stream(preparation, output);

        String frames = output.toString(StandardCharsets.UTF_8);
        assertThat(frames).contains("event: meta", "event: delta", "event: done", "\"messageId\":77")
                .doesNotContain("event: error");
        assertThat(frames.indexOf("event: meta")).isLessThan(frames.indexOf("event: delta"));
        assertThat(frames.indexOf("event: delta")).isLessThan(frames.indexOf("event: done"));
    }

    @Test
    void persistsPartialTextAsFailedAndEndsWithErrorWithoutDone() {
        ConversationService conversations = mock(ConversationService.class);
        ConversationStreamingService service = new ConversationStreamingService(conversations, new ObjectMapper());
        AgentTurnPreparation preparation = preparation("阅读", List.of(), consumer -> {
            consumer.accept("部分正文");
            throw new DependencyUnavailableException("Chat service");
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        service.stream(preparation, output);

        String frames = output.toString(StandardCharsets.UTF_8);
        assertThat(frames).contains("event: meta", "event: delta", "event: error", "STREAM_FAILED")
                .doesNotContain("event: done");
        verify(conversations).saveAssistantMessage(
                9L, 6L, 2L, "部分正文", "阅读", List.of(), MessageStatus.FAILED);
    }

    @Test
    void doesNotPersistEmptyAssistantAndFiltersNoEvidenceMarkersAcrossDeltas() {
        ConversationService conversations = mock(ConversationService.class);
        ConversationMessage saved = mock(ConversationMessage.class);
        when(saved.getId()).thenReturn(88L);
        when(conversations.saveAssistantMessage(
                eq(9L), eq(6L), eq(2L), eq("无依据回答。"), eq(null), any(), eq(MessageStatus.COMPLETED)))
                .thenReturn(saved);
        ConversationStreamingService service = new ConversationStreamingService(conversations, new ObjectMapper());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.stream(preparation(null, List.of(), consumer -> {
            consumer.accept("无依据[");
            consumer.accept("2]回答。");
        }), output);

        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain("[2]");
        verify(conversations).saveAssistantMessage(
                9L, 6L, 2L, "无依据回答。", null, List.of(), MessageStatus.COMPLETED);

        ConversationService emptyConversations = mock(ConversationService.class);
        ByteArrayOutputStream failed = new ByteArrayOutputStream();
        new ConversationStreamingService(emptyConversations, new ObjectMapper()).stream(
                preparation(null, List.of(), consumer -> {
                    throw new DependencyUnavailableException("Chat service");
                }), failed);
        verify(emptyConversations, never()).saveAssistantMessage(any(), any(), any(), any(), any(), any(), any());
        assertThat(failed.toString(StandardCharsets.UTF_8)).contains("event: error").doesNotContain("event: done");
    }

    @Test
    void sendsErrorWithoutDoneWhenCompletedMessageCommitFails() {
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.saveAssistantMessage(
                eq(9L), eq(6L), eq(2L), eq("完整正文"), eq("阅读"), any(), eq(MessageStatus.COMPLETED)))
                .thenThrow(new IllegalStateException("database details"));
        when(conversations.saveAssistantMessage(
                eq(9L), eq(6L), eq(2L), eq("完整正文"), eq("阅读"), any(), eq(MessageStatus.FAILED)))
                .thenThrow(new IllegalStateException("database details"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new ConversationStreamingService(conversations, new ObjectMapper()).stream(
                preparation("阅读", List.of(), consumer -> consumer.accept("完整正文")), output);

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("event: meta", "event: delta", "event: error", "回答生成中断")
                .doesNotContain("event: done", "database details");
    }

    @Test
    void persistsPartialTextWhenClientDisconnectsAfterDelta() {
        ConversationService conversations = mock(ConversationService.class);
        ConversationStreamingService service = new ConversationStreamingService(conversations, new ObjectMapper());
        OutputStream output = new OutputStream() {
            private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
            private int frames;

            @Override
            public void write(int value) throws IOException {
                delegate.write(value);
                if (value == '\n' && delegate.toString(StandardCharsets.UTF_8).endsWith("\n\n")) {
                    frames++;
                    if (frames >= 2) throw new IOException("client disconnected");
                }
            }
        };

        service.stream(preparation("阅读", List.of(), consumer -> consumer.accept("部分正文")), output);

        verify(conversations).saveAssistantMessage(
                9L, 6L, 2L, "部分正文", "阅读", List.of(), MessageStatus.FAILED);
        verify(conversations, never()).saveAssistantMessage(
                eq(9L), eq(6L), eq(2L), any(), any(), any(), eq(MessageStatus.COMPLETED));
    }

    private AgentTurnPreparation preparation(String skillName, List<ConversationCitation> citations,
                                             StreamAction action) {
        ChatGateway.ChatStream stream = new ChatGateway.ChatStream() {
            @Override
            public void consume(Consumer<String> consumer) { action.run(consumer); }

            @Override
            public void close() { }
        };
        AgentTurnPlan plan = new AgentTurnPlan(skillName, citations,
                List.of(new ChatGateway.ChatMessage("system", "规则")));
        return new AgentTurnPreparation(9L, 6L, 2L, plan, stream);
    }

    @FunctionalInterface
    private interface StreamAction {
        void run(Consumer<String> consumer);
    }
}
