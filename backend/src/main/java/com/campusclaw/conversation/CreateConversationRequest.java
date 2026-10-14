package com.campusclaw.conversation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateConversationRequest(@NotNull @Positive Long assistantId) {
}
