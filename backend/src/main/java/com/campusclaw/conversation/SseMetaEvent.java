package com.campusclaw.conversation;

import java.util.List;

public record SseMetaEvent(
        String status,
        String skillName,
        List<ConversationCitation> citations) {
}
