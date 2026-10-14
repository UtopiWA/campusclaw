package com.campusclaw.agent;

import jakarta.validation.constraints.NotBlank;

public record SkillSourceRequest(@NotBlank String sourceText) {
}
