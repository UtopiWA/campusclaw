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
@Table(name = "assistants")
public class Assistant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "prompt_text", length = 2000)
    private String promptText;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Assistant() {
    }

    public Assistant(Long classId, String name) {
        this(classId, name, null, true);
    }

    public Assistant(Long classId, String name, String promptText, boolean enabled) {
        this.classId = classId;
        this.name = name;
        this.promptText = promptText;
        this.enabled = enabled;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** 配置更新由服务层在完成全部绑定校验后一次性应用。 */
    public void update(String name, String promptText, boolean enabled) {
        this.name = name;
        this.promptText = promptText;
        this.enabled = enabled;
    }

    public Long getId() { return id; }
    public Long getClassId() { return classId; }
    public String getName() { return name; }
    public String getPromptText() { return promptText; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
