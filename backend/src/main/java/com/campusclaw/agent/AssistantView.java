package com.campusclaw.agent;

import com.campusclaw.persistence.Assistant;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantView(
        Long id,
        String name,
        Boolean enabled,
        String promptText,
        Set<Long> skillIds,
        Set<Long> materialIds) {

    static AssistantView teacher(Assistant assistant, Set<Long> skillIds, Set<Long> materialIds) {
        return new AssistantView(assistant.getId(), assistant.getName(), assistant.isEnabled(),
                assistant.getPromptText() == null ? "" : assistant.getPromptText(), skillIds, materialIds);
    }

    static AssistantView student(Assistant assistant) {
        return new AssistantView(assistant.getId(), assistant.getName(), true, null, null, null);
    }
}
