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

/** 保存可追溯检索正文；向量库仅保存本实体编号与隔离过滤字段。 */
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "knowledge_entry_id", nullable = false)
    private Long knowledgeEntryId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false)
    private IndexStatus indexStatus;

    @Column(name = "index_error", length = 500)
    private String indexError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeChunk() {
    }

    public KnowledgeChunk(Long materialId, Long knowledgeEntryId, Long classId, int chunkIndex,
                          String chunkText, int startOffset, int endOffset) {
        this.materialId = materialId;
        this.knowledgeEntryId = knowledgeEntryId;
        this.classId = classId;
        this.chunkIndex = chunkIndex;
        this.chunkText = chunkText;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.indexStatus = IndexStatus.PENDING;
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

    public void markReady() {
        indexStatus = IndexStatus.READY;
        indexError = null;
    }

    public void markFailed(String message) {
        indexStatus = IndexStatus.FAILED;
        indexError = message == null ? "Indexing failed" : message.substring(0, Math.min(500, message.length()));
    }

    public Long getId() { return id; }
    public Long getMaterialId() { return materialId; }
    public Long getKnowledgeEntryId() { return knowledgeEntryId; }
    public Long getClassId() { return classId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getChunkText() { return chunkText; }
    public int getStartOffset() { return startOffset; }
    public int getEndOffset() { return endOffset; }
    public IndexStatus getIndexStatus() { return indexStatus; }
}
