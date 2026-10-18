package com.campusclaw.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {
    List<McpServer> findAllByClassIdOrderByCreatedAtAsc(Long classId);
    Optional<McpServer> findByIdAndClassId(Long id, Long classId);
    Optional<McpServer> findFirstByClassIdAndName(Long classId, String name);
    boolean existsByClassIdAndNameAndIdNot(Long classId, String name, Long id);
}
