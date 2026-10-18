package com.campusclaw.conversation;

import com.campusclaw.persistence.ConversationMessage;
import java.time.Instant;
import java.util.List;
import com.campusclaw.mcp.ToolCallSummary;

public record MessageView(
        Long id,
        String role,
        String content,
        String skillName,
        List<ConversationCitation> citations,
        List<ToolCallSummary> toolCalls,
        String status,
        Instant createdAt) {

    static MessageView from(ConversationMessage message, List<ConversationCitation> citations,
                            List<ToolCallSummary> toolCalls) {
        return new MessageView(message.getId(), message.getRole().name().toLowerCase(), message.getContent(),
                message.getSkillName(), citations, toolCalls, message.getStatus().name(), message.getCreatedAt());
    }
}
