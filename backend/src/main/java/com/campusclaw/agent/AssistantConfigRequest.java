package com.campusclaw.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record AssistantConfigRequest(
        @NotBlank String name,
        String promptText,
        @NotNull Boolean enabled,
        Set<Long> skillIds,
        Set<Long> materialIds) {
}
