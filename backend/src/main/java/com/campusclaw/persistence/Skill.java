package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "skills")
public class Skill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(name = "trigger_text", nullable = false, length = 120)
    private String triggerText;

    @Column(name = "material_scope", nullable = false, length = 32)
    private String materialScope;

    @Column(name = "tool_refs", nullable = false, columnDefinition = "TEXT")
    private String toolRefs;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 2000)
    private String instructions;

    @Column(name = "source_text", nullable = false, columnDefinition = "TEXT")
    private String sourceText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Skill() {
    }

    public Skill(Long classId, String name, String triggerText, String materialScope,
                 String toolRefs, boolean enabled, String instructions, String sourceText) {
        this.classId = classId;
        this.name = name;
        this.triggerText = triggerText;
        this.materialScope = materialScope;
        this.toolRefs = toolRefs;
        this.enabled = enabled;
        this.instructions = instructions;
        this.sourceText = sourceText;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    /** 原文重新解析成功后才整体覆盖结构化字段，避免半成品配置。 */
    public void update(String name, String triggerText, String materialScope,
                       String toolRefs, boolean enabled, String instructions, String sourceText) {
        this.name = name;
        this.triggerText = triggerText;
        this.materialScope = materialScope;
        this.toolRefs = toolRefs;
        this.enabled = enabled;
        this.instructions = instructions;
        this.sourceText = sourceText;
    }

    public Long getId() { return id; }
    public Long getClassId() { return classId; }
    public String getName() { return name; }
    public String getTriggerText() { return triggerText; }
    public String getMaterialScope() { return materialScope; }
    public String getToolRefs() { return toolRefs; }
    public boolean isEnabled() { return enabled; }
    public String getInstructions() { return instructions; }
    public String getSourceText() { return sourceText; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
