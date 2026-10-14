package com.campusclaw.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    List<Material> findAllByClassIdOrderByCreatedAtDesc(Long classId);
    Optional<Material> findByIdAndClassId(Long id, Long classId);
    List<Material> findAllByIdInAndClassId(Collection<Long> ids, Long classId);
    Optional<Material> findFirstByClassIdAndTitleAndStoredPathIsNull(Long classId, String title);
    List<Material> findTop20ByIndexStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            IndexStatus status, Instant updatedBefore);

    /** 条件更新是后台任务的唯一认领入口，避免多个调度来源同时解析同一材料。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE materials
            SET index_status = 'INDEXING', processing_stage = 'PARSING',
                index_error = NULL, failure_code = NULL, retryable = FALSE, updated_at = :now
            WHERE id = :id AND class_id = :classId AND index_status = 'PENDING'
            """, nativeQuery = true)
    int claimPending(@Param("id") Long id, @Param("classId") Long classId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE materials
            SET index_status = 'PENDING', processing_stage = 'QUEUED', updated_at = :now
            WHERE id = :id AND class_id = :classId
              AND index_status = 'INDEXING' AND updated_at < :staleBefore
            """, nativeQuery = true)
    int requeueStale(@Param("id") Long id, @Param("classId") Long classId,
                     @Param("staleBefore") Instant staleBefore, @Param("now") Instant now);
}
