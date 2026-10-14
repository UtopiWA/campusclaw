package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/** 使用显式关联实体保存智能体允许检索的材料白名单。 */
@Entity
@Table(name = "assistant_materials")
@IdClass(AssistantMaterial.Key.class)
public class AssistantMaterial {
    @Id
    @Column(name = "assistant_id", nullable = false)
    private Long assistantId;

    @Id
    @Column(name = "material_id", nullable = false)
    private Long materialId;

    protected AssistantMaterial() {
    }

    public AssistantMaterial(Long assistantId, Long materialId) {
        this.assistantId = assistantId;
        this.materialId = materialId;
    }

    public Long getAssistantId() { return assistantId; }
    public Long getMaterialId() { return materialId; }

    public static class Key implements Serializable {
        private Long assistantId;
        private Long materialId;

        public Key() {
        }

        public Key(Long assistantId, Long materialId) {
            this.assistantId = assistantId;
            this.materialId = materialId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Key key)) return false;
            return Objects.equals(assistantId, key.assistantId) && Objects.equals(materialId, key.materialId);
        }

        @Override
        public int hashCode() { return Objects.hash(assistantId, materialId); }
    }
}
