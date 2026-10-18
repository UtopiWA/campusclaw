package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "mcp_tools")
public class McpTool {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "server_id", nullable = false)
    private Long serverId;
    @Column(nullable = false, length = 64)
    private String name;
    @Column(nullable = false, length = 1000)
    private String description;
    @Column(name = "input_schema", nullable = false, columnDefinition = "JSON")
    private String inputSchema;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected McpTool() { }
    public McpTool(Long serverId, String name, String description, String inputSchema) {
        this.serverId = serverId;
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }
    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getServerId() { return serverId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getInputSchema() { return inputSchema; }
    public Instant getCreatedAt() { return createdAt; }
}
