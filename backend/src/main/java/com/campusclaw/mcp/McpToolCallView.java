package com.campusclaw.mcp;

import com.campusclaw.persistence.McpToolCall;
import java.time.Instant;

public record McpToolCallView(Long id, Long serverId, Long userId, String userName, Long conversationId,
                              String toolRef, String status, String detail, Long durationMs, Instant createdAt) {
    static McpToolCallView from(McpToolCall call, String userName) {
        return new McpToolCallView(call.getId(), call.getServerId(), call.getUserId(), userName,
                call.getConversationId(), call.getToolRef(), call.getStatus().name().toLowerCase(),
                call.getDetail(), call.getDurationMs(), call.getCreatedAt());
    }
}
