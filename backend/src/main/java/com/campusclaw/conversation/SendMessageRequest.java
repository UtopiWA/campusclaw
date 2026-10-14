package com.campusclaw.conversation;

import jakarta.validation.constraints.NotBlank;

public record SendMessageRequest(@NotBlank String content) {
}
