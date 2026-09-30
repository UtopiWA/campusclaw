package com.campusclaw.knowledge;

import com.campusclaw.gateway.VectorStoreGateway;
import com.campusclaw.persistence.VectorCleanupJob;
import com.campusclaw.persistence.VectorCleanupJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提交后立即清理向量，并周期性重试删除事务中持久化的清理意图。 */
@Service
public class VectorCleanupService {
    private final VectorCleanupJobRepository jobs;
    private final VectorStoreGateway vectors;
    private final ObjectMapper objectMapper;

    public VectorCleanupService(VectorCleanupJobRepository jobs, VectorStoreGateway vectors,
                                ObjectMapper objectMapper) {
        this.jobs = jobs;
        this.vectors = vectors;
        this.objectMapper = objectMapper;
    }

    public void processMaterial(Long materialId) {
        jobs.findAllByMaterialId(materialId).forEach(this::process);
    }

    @Scheduled(fixedDelayString = "${app.retrieval.cleanup-interval:PT1M}")
    public void retryDueJobs() {
        jobs.findAllByNextRetryAtIsNullOrNextRetryAtBefore(Instant.now()).forEach(this::process);
    }

    @Transactional
    protected void process(VectorCleanupJob job) {
        try {
            List<Long> ids = objectMapper.readValue(job.getPointIds(), new TypeReference<>() { });
            vectors.deletePoints(ids);
            vectors.deleteMaterial(job.getMaterialId());
            jobs.delete(job);
        } catch (Exception exception) {
            job.retryLater();
            jobs.save(job);
        }
    }
}
