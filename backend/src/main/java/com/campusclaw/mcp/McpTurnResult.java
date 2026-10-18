package com.campusclaw.mcp;

import java.util.List;

public record McpTurnResult(List<ToolCallSummary> calls, List<ToolContext> usableContexts) {
    public static McpTurnResult empty() { return new McpTurnResult(List.of(), List.of()); }
    public boolean hasUsableContext() { return !usableContexts.isEmpty(); }
    public record ToolContext(String ref, String text) { }
}
