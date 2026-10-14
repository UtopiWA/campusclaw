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

/** 服务端持久化的私人会话，会话创建后固定智能体。 */
@Entity
@Table(name = "conversations")
public class Conversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "assistant_id", nullable = false)
    private Long assistantId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
    }

    public Conversation(Long classId, Long userId, Long assistantId, String title) {
        this.classId = classId;
        this.userId = userId;
        this.assistantId = assistantId;
        this.title = title;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public void updateTitle(String title) { this.title = title; }
    public void touch() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getClassId() { return classId; }
    public Long getUserId() { return userId; }
    public Long getAssistantId() { return assistantId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
