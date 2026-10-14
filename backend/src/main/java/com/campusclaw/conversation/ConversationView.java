package com.campusclaw.conversation;

import com.campusclaw.persistence.Conversation;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationView(
        Long id,
        String title,
        Long assistantId,
        String assistantName,
        Instant createdAt,
        Instant updatedAt,
        List<MessageView> messages) {

    static ConversationView summary(Conversation conversation, String assistantName) {
        return new ConversationView(conversation.getId(), conversation.getTitle(), conversation.getAssistantId(),
                assistantName, conversation.getCreatedAt(), conversation.getUpdatedAt(), null);
    }

    static ConversationView detail(
            Conversation conversation, String assistantName, List<MessageView> messages) {
        return new ConversationView(conversation.getId(), conversation.getTitle(), conversation.getAssistantId(),
                assistantName, conversation.getCreatedAt(), conversation.getUpdatedAt(), messages);
    }
}
