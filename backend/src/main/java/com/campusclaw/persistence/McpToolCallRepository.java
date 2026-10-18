package com.campusclaw.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpToolCallRepository extends JpaRepository<McpToolCall, Long> {
    boolean existsByServerId(Long serverId);
    List<McpToolCall> findAllByClassIdOrderByCreatedAtDesc(Long classId, Pageable pageable);
}
