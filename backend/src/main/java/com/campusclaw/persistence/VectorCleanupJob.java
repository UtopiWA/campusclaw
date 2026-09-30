package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/** 独立保存向量清理意图，材料删除后任务仍可重试。 */
@Entity
@Table(name = "vector_cleanup_jobs")
public class VectorCleanupJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "point_ids", nullable = false, columnDefinition = "JSON")
    private String pointIds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CleanupStatus status = CleanupStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected VectorCleanupJob() {
    }

    public VectorCleanupJob(Long materialId, String pointIds) {
        this.materialId = materialId;
        this.pointIds = pointIds;
    }

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public void retryLater() {
        retryCount++;
        status = CleanupStatus.RETRYING;
        nextRetryAt = Instant.now().plusSeconds(Math.min(3600, 30L * retryCount));
        lastError = "Vector cleanup dependency unavailable";
    }

    public Long getId() { return id; }
    public Long getMaterialId() { return materialId; }
    public String getPointIds() { return pointIds; }
    public int getRetryCount() { return retryCount; }
}
