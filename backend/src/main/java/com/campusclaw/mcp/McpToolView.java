package com.campusclaw.mcp;

import com.campusclaw.persistence.McpTool;
import com.fasterxml.jackson.databind.JsonNode;

public record McpToolView(Long id, String name, String description, JsonNode inputSchema) {
}
