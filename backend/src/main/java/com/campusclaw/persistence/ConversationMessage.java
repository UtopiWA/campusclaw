package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** 保存可恢复的消息正文，以及当轮技能和引用展示快照。 */
@Entity
@Table(name = "messages")
public class ConversationMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "skill_name", length = 255)
    private String skillName;

    @Column(name = "citations_json", nullable = false, columnDefinition = "JSON")
    private String citationsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ConversationMessage() {
    }

    public ConversationMessage(Long conversationId, MessageRole role, String content,
                               String skillName, String citationsJson, MessageStatus status) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.skillName = skillName;
        this.citationsJson = citationsJson;
        this.status = status;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public MessageRole getRole() { return role; }
    public String getContent() { return content; }
    public String getSkillName() { return skillName; }
    public String getCitationsJson() { return citationsJson; }
    public MessageStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
