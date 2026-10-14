package com.campusclaw.conversation;

import com.campusclaw.gateway.ChatGateway;
import java.util.List;

public record AgentTurnPlan(
        String skillName,
        List<ConversationCitation> citations,
        List<ChatGateway.ChatMessage> messages) {
}
