package com.campusclaw.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record McpCreateServerRequest(
        @NotBlank @Size(max = 32) String name,
        @NotBlank @Size(max = 512) String baseUrl,
        @NotBlank @Size(max = 512) String token) {
}
