package com.campusclaw.conversation;

import com.campusclaw.persistence.ConversationMessage;
import com.campusclaw.persistence.MessageStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 执行已完成首帧前准备的流式输出，并按完成或中断状态持久化助手消息。 */
@Service
public class ConversationStreamingService {
    private static final Logger log = LoggerFactory.getLogger(ConversationStreamingService.class);

    private final ConversationService conversations;
    private final ObjectMapper objectMapper;

    public ConversationStreamingService(ConversationService conversations, ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.objectMapper = objectMapper;
    }

    public void stream(AgentTurnPreparation preparation, OutputStream output) {
        SseEventWriter writer = new SseEventWriter(output, objectMapper);
        StringBuilder visibleText = new StringBuilder();
        boolean[] completedMessage = {false};
        AgentTurnPlan plan = preparation.plan();
        CitationMarkerFilter markerFilter = plan.citations().isEmpty() ? new CitationMarkerFilter() : null;
        try (var upstream = preparation.stream()) {
            writer.write("meta", new SseMetaEvent("ready", plan.skillName(), plan.citations()));
            upstream.consume(delta -> appendDelta(delta, markerFilter, visibleText, writer));
            if (markerFilter != null) {
                appendVisible(markerFilter.finish(), visibleText, writer);
            }
            if (visibleText.toString().isBlank()) {
                throw new IllegalStateException("Chat stream produced no visible text");
            }
            ConversationMessage saved = conversations.saveAssistantMessage(
                    preparation.conversationId(), preparation.userId(), preparation.classId(),
                    visibleText.toString(), plan.skillName(), plan.citations(), MessageStatus.COMPLETED);
            completedMessage[0] = true;
            writer.write("done", Map.of("messageId", saved.getId()));
        } catch (RuntimeException exception) {
            if (!completedMessage[0] && !visibleText.toString().isBlank()) {
                persistFailed(preparation, visibleText.toString());
            }
            if (!completedMessage[0]) {
                try {
                    writer.write("error", Map.of(
                            "code", "STREAM_FAILED",
                            "message", "回答生成中断，请重试"));
                } catch (RuntimeException writeFailure) {
                    log.warn("Conversation stream {} disconnected before error event", preparation.conversationId());
                }
            } else {
                log.warn("Conversation stream {} disconnected after assistant message commit",
                        preparation.conversationId());
            }
        }
    }

    private void appendDelta(String delta, CitationMarkerFilter filter,
                             StringBuilder visibleText, SseEventWriter writer) {
        String visible = filter == null ? delta : filter.accept(delta);
        appendVisible(visible, visibleText, writer);
    }

    private void appendVisible(String visible, StringBuilder fullText, SseEventWriter writer) {
        if (visible == null || visible.isEmpty()) {
            return;
        }
        fullText.append(visible);
        writer.write("delta", Map.of("text", visible));
    }

    private void persistFailed(AgentTurnPreparation preparation, String partialText) {
        try {
            conversations.saveAssistantMessage(
                    preparation.conversationId(), preparation.userId(), preparation.classId(),
                    partialText, preparation.plan().skillName(), preparation.plan().citations(), MessageStatus.FAILED);
        } catch (RuntimeException persistenceFailure) {
            log.warn("Could not persist interrupted assistant message for conversation {}",
                    preparation.conversationId());
        }
    }
}
