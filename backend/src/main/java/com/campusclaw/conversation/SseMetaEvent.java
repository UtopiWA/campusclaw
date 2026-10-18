package com.campusclaw.conversation;

import java.util.List;
import com.campusclaw.mcp.ToolCallSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

public record SseMetaEvent(
        String status,
        String skillName,
        List<ConversationCitation> citations,
        @io.swagger.v3.oas.annotations.media.Schema(
                description = "按技能配置顺序排列；没有 MCP 引用时从 meta 事件中省略")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ToolCallSummary> toolCalls) {
}
