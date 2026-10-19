package com.campusclaw.mcp;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "不含凭据、参数或原始响应的 MCP 调用安全摘要")
public record ToolCallSummary(
        @Schema(example = "calculator@demo") String ref,
        @Schema(allowableValues = {"ok", "denied", "error"}, example = "ok") String status,
        @Schema(example = "2 km = 2000 m") String detail) {
}
