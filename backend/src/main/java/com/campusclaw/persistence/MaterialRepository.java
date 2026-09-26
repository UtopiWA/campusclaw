package com.campusclaw.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    List<Material> findAllByClassIdOrderByCreatedAtDesc(Long classId);
    Optional<Material> findByIdAndClassId(Long id, Long classId);
    Optional<Material> findFirstByClassIdAndTitleAndStoredPathIsNull(Long classId, String title);
}

