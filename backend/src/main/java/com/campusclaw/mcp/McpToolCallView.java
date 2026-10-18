package com.campusclaw.mcp;

import com.campusclaw.persistence.McpToolCall;
import java.time.Instant;

public record McpToolCallView(Long id, Long serverId, Long userId, Long conversationId,
                              String toolRef, String status, String detail, Instant createdAt) {
    static McpToolCallView from(McpToolCall call) {
        return new McpToolCallView(call.getId(), call.getServerId(), call.getUserId(), call.getConversationId(),
                call.getToolRef(), call.getStatus().name().toLowerCase(), call.getDetail(), call.getCreatedAt());
    }
}
