package com.campusclaw.conversation;

import com.campusclaw.gateway.ChatGateway;
import com.campusclaw.mcp.ToolCallSummary;
import java.util.List;

public record AgentTurnPlan(
        String skillName,
        List<ConversationCitation> citations,
        List<ToolCallSummary> toolCalls,
        List<ChatGateway.ChatMessage> messages) {

    public AgentTurnPlan(String skillName, List<ConversationCitation> citations,
                         List<ChatGateway.ChatMessage> messages) {
        this(skillName, citations, List.of(), messages);
    }
}
