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

@Entity
@Table(name = "mcp_tool_calls")
public class McpToolCall {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "class_id", nullable = false)
    private Long classId;
    @Column(name = "server_id", nullable = false)
    private Long serverId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "conversation_id")
    private Long conversationId;
    @Column(name = "tool_ref", nullable = false, length = 128)
    private String toolRef;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private McpToolCallStatus status;
    @Column(nullable = false, length = 512)
    private String detail;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected McpToolCall() { }
    public McpToolCall(Long classId, Long serverId, Long userId, Long conversationId,
                       String toolRef, McpToolCallStatus status, String detail) {
        this.classId = classId;
        this.serverId = serverId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.toolRef = toolRef;
        this.status = status;
        this.detail = detail;
    }
    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getClassId() { return classId; }
    public Long getServerId() { return serverId; }
    public Long getUserId() { return userId; }
    public Long getConversationId() { return conversationId; }
    public String getToolRef() { return toolRef; }
    public McpToolCallStatus getStatus() { return status; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
