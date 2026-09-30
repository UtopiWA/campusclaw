package com.campusclaw.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {
    List<KnowledgeChunk> findAllByMaterialIdAndClassIdOrderByChunkIndex(Long materialId, Long classId);

    List<KnowledgeChunk> findAllByIdInAndClassIdAndIndexStatus(Collection<Long> ids, Long classId, IndexStatus status);

    @Query(value = """
            SELECT kc.id AS id,
                   MATCH(kc.chunk_text) AGAINST (:query IN NATURAL LANGUAGE MODE) AS keywordScore
            FROM knowledge_chunks kc
            JOIN materials m ON m.id = kc.material_id
            WHERE kc.class_id = :classId
              AND kc.index_status = 'READY'
              AND m.index_status = 'READY'
              AND MATCH(kc.chunk_text) AGAINST (:query IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(kc.chunk_text) AGAINST (:query IN NATURAL LANGUAGE MODE) DESC, kc.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<KeywordHitProjection> searchReady(@Param("classId") Long classId,
                                           @Param("query") String query,
                                           @Param("limit") int limit);

    @Modifying
    void deleteAllByMaterialId(Long materialId);

    interface KeywordHitProjection {
        Long getId();
        Double getKeywordScore();
    }
}
