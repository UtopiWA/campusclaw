package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 使用显式关联实体保存可复用技能绑定，班级一致性由配置服务校验。 */
@Entity
@Table(name = "assistant_skills")
@IdClass(AssistantSkill.Key.class)
public class AssistantSkill {
    @Id
    @Column(name = "assistant_id", nullable = false)
    private Long assistantId;

    @Id
    @Column(name = "skill_id", nullable = false)
    private Long skillId;

    protected AssistantSkill() {
    }

    public AssistantSkill(Long assistantId, Long skillId) {
        this.assistantId = assistantId;
        this.skillId = skillId;
    }

    public Long getAssistantId() { return assistantId; }
    public Long getSkillId() { return skillId; }

    public static class Key implements Serializable {
        private Long assistantId;
        private Long skillId;

        public Key() {
        }

        public Key(Long assistantId, Long skillId) {
            this.assistantId = assistantId;
            this.skillId = skillId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Key key)) return false;
            return Objects.equals(assistantId, key.assistantId) && Objects.equals(skillId, key.skillId);
        }

        @Override
        public int hashCode() { return Objects.hash(assistantId, skillId); }
    }
}
