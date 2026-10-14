package com.campusclaw.agent;

import com.campusclaw.persistence.Skill;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillView(
        Long id,
        String name,
        String triggerText,
        String materialScope,
        String toolName,
        boolean enabled,
        String instructions,
        String sourceText) {

    static SkillView from(Skill skill, boolean includeEditableContent) {
        return new SkillView(
                skill.getId(), skill.getName(), skill.getTriggerText(), skill.getMaterialScope(),
                skill.getToolName(), skill.isEnabled(),
                includeEditableContent ? skill.getInstructions() : null,
                includeEditableContent ? skill.getSourceText() : null);
    }
}
