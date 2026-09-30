package com.campusclaw.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VectorCleanupJobRepository extends JpaRepository<VectorCleanupJob, Long> {
    List<VectorCleanupJob> findAllByNextRetryAtIsNullOrNextRetryAtBefore(Instant now);
    List<VectorCleanupJob> findAllByMaterialId(Long materialId);
}
