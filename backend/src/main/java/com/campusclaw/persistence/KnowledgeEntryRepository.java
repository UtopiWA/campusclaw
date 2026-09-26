package com.campusclaw.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, Long> {
    List<KnowledgeEntry> findAllByMaterialIdAndClassIdOrderByChunkIndex(Long materialId, Long classId);
}

