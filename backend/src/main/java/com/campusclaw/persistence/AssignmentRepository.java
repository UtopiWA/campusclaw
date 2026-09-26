package com.campusclaw.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {
    Optional<Assignment> findFirstByClassIdAndTitle(Long classId, String title);
}

