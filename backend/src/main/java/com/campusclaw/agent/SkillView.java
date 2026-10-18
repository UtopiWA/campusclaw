package com.campusclaw.agent;

import com.campusclaw.persistence.Skill;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillView(
        Long id,
        String name,
        String triggerText,
        String materialScope,
        List<String> toolRefs,
        boolean enabled,
        String instructions,
        String sourceText) {

    static SkillView from(Skill skill, boolean includeEditableContent) {
        return new SkillView(
                skill.getId(), skill.getName(), skill.getTriggerText(), skill.getMaterialScope(),
                splitToolRefs(skill.getToolRefs()), skill.isEnabled(),
                includeEditableContent ? skill.getInstructions() : null,
                includeEditableContent ? skill.getSourceText() : null);
    }

    private static List<String> splitToolRefs(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }
}
