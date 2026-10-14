package com.campusclaw.conversation;

import com.campusclaw.gateway.ChatGateway;

public record AgentTurnPreparation(
        Long conversationId,
        Long userId,
        Long classId,
        AgentTurnPlan plan,
        ChatGateway.ChatStream stream) {
}
