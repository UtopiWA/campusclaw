package com.campusclaw.conversation;

import com.campusclaw.persistence.ConversationMessage;
import java.time.Instant;
import java.util.List;

public record MessageView(
        Long id,
        String role,
        String content,
        String skillName,
        List<ConversationCitation> citations,
        String status,
        Instant createdAt) {

    static MessageView from(ConversationMessage message, List<ConversationCitation> citations) {
        return new MessageView(message.getId(), message.getRole().name().toLowerCase(), message.getContent(),
                message.getSkillName(), citations, message.getStatus().name(), message.getCreatedAt());
    }
}
