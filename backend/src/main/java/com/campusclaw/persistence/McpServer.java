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
@Table(name = "mcp_servers")
public class McpServer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "class_id", nullable = false)
    private Long classId;
    @Column(nullable = false, length = 32)
    private String name;
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;
    @Column(name = "auth_token_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String authTokenCiphertext;
    @Column(nullable = false)
    private boolean connected;
    @Column(name = "connected_at")
    private Instant connectedAt;
    @Column(name = "config_version", nullable = false)
    private long configVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected McpServer() { }

    public McpServer(Long classId, String name, String baseUrl, String authTokenCiphertext) {
        this.classId = classId;
        this.name = name;
        this.baseUrl = baseUrl;
        this.authTokenCiphertext = authTokenCiphertext;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public void updateConfiguration(String name, String baseUrl, String authTokenCiphertext) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.authTokenCiphertext = authTokenCiphertext;
        this.connected = false;
        this.connectedAt = null;
        this.configVersion++;
    }

    public void markConnected() {
        connected = true;
        connectedAt = Instant.now();
    }

    public void markDisconnected() {
        connected = false;
        connectedAt = null;
    }

    public void disconnectAndInvalidate() {
        markDisconnected();
        configVersion++;
    }

    public Long getId() { return id; }
    public Long getClassId() { return classId; }
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public String getAuthTokenCiphertext() { return authTokenCiphertext; }
    public boolean isConnected() { return connected; }
    public Instant getConnectedAt() { return connectedAt; }
    public long getConfigVersion() { return configVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
