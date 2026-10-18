package com.campusclaw.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpToolRepository extends JpaRepository<McpTool, Long> {
    List<McpTool> findAllByServerIdOrderByIdAsc(Long serverId);
    Optional<McpTool> findFirstByServerIdAndName(Long serverId, String name);
    long countByServerId(Long serverId);
    void deleteAllByServerId(Long serverId);
}
