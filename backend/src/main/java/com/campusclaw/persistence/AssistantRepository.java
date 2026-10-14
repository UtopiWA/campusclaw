package com.campusclaw.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantRepository extends JpaRepository<Assistant, Long> {
    Optional<Assistant> findFirstByClassIdAndName(Long classId, String name);
    Optional<Assistant> findByIdAndClassId(Long id, Long classId);
    Optional<Assistant> findByIdAndClassIdAndEnabledTrue(Long id, Long classId);
    List<Assistant> findAllByClassIdOrderByCreatedAtAsc(Long classId);
    List<Assistant> findAllByClassIdAndEnabledTrueOrderByCreatedAtAsc(Long classId);
    List<Assistant> findAllByIdInAndClassId(Collection<Long> ids, Long classId);
    boolean existsByClassIdAndNameAndIdNot(Long classId, String name, Long id);
}
