package com.campusclaw.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_entries")
public class KnowledgeEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    protected KnowledgeEntry() {
    }

    public KnowledgeEntry(Long materialId, Long classId, int chunkIndex, String bodyText) {
        this.materialId = materialId;
        this.classId = classId;
        this.chunkIndex = chunkIndex;
        this.bodyText = bodyText;
    }

    public Long getId() {
        return id;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public Long getClassId() {
        return classId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getBodyText() {
        return bodyText;
    }
}

