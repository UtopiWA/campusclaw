package com.campusclaw.agent;

import java.util.List;

/** 技能原文经严格解析后的可信结构，原文自身仍视为不可信数据。 */
public record SkillDefinition(
        String name,
        List<String> triggers,
        String materialScope,
        String toolName,
        boolean enabled,
        String instructions,
        String sourceText) {

    public String triggerText() {
        return String.join(",", triggers);
    }
}
