package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "materials")
public class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "stored_path", length = 500)
    private String storedPath;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false)
    private IndexStatus indexStatus = IndexStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_strategy", nullable = false)
    private IndexStrategy indexStrategy = IndexStrategy.AUTO;

    @Column(name = "index_error", length = 500)
    private String indexError;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Material() {
    }

    public Material(Long classId, String title, String originalFilename, String storedPath, Long uploadedBy) {
        this.classId = classId;
        this.title = title;
        this.originalFilename = originalFilename;
        this.storedPath = storedPath;
        this.uploadedBy = uploadedBy;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getClassId() {
        return classId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStoredPath() {
        return storedPath;
    }

    public void setStoredPath(String storedPath) {
        this.storedPath = storedPath;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(Long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public IndexStatus getIndexStatus() {
        return indexStatus;
    }

    public IndexStrategy getIndexStrategy() {
        return indexStrategy;
    }

    public String getIndexError() {
        return indexError;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }

    /** 索引状态只通过领域方法切换，避免业务层遗漏错误信息和完成时间的联动更新。 */
    public void markIndexing(IndexStrategy strategy) {
        this.indexStatus = IndexStatus.INDEXING;
        this.indexStrategy = strategy;
        this.indexError = null;
        this.indexedAt = null;
    }

    public void markReady() {
        this.indexStatus = IndexStatus.READY;
        this.indexError = null;
        this.indexedAt = Instant.now();
    }

    public void markFailed(String message) {
        this.indexStatus = IndexStatus.FAILED;
        this.indexError = message == null ? "Indexing failed" : message.substring(0, Math.min(500, message.length()));
        this.indexedAt = null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
