package com.campusclaw.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantRepository extends JpaRepository<Assistant, Long> {
    Optional<Assistant> findFirstByClassIdAndName(Long classId, String name);
}

