package com.campusclaw.knowledge;

import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 小批量恢复遗留任务，调度线程只提交标识，不执行解析或远程调用。 */
@Component
@ConditionalOnProperty(prefix = "app.retrieval", name = "initialize-vector-store", havingValue = "true")
public class MaterialRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(MaterialRecoveryScheduler.class);
    private static final Duration PENDING_GRACE = Duration.ofSeconds(30);
    private static final Duration INDEXING_STALE_AFTER = Duration.ofMinutes(30);

    private final MaterialRepository materials;
    private final IndexStateService state;
    private final MaterialIngestionService ingestion;

    public MaterialRecoveryScheduler(MaterialRepository materials, IndexStateService state,
                                     MaterialIngestionService ingestion) {
        this.materials = materials;
        this.state = state;
        this.ingestion = ingestion;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void afterApplicationReady() {
        recover();
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void scheduledRecovery() {
        recover();
    }

    void recover() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(INDEXING_STALE_AFTER);
        Map<Long, Material> candidates = new LinkedHashMap<>();
        for (Material stale : materials.findTop20ByIndexStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                IndexStatus.INDEXING, staleBefore)) {
            if (state.requeueStale(stale.getId(), stale.getClassId(), staleBefore)) {
                candidates.put(stale.getId(), stale);
            }
        }
        for (Material pending : materials.findTop20ByIndexStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                IndexStatus.PENDING, now.minus(PENDING_GRACE))) {
            candidates.putIfAbsent(pending.getId(), pending);
        }
        for (Material material : candidates.values()) {
            try {
                ingestion.ingestAsync(material.getId(), material.getClassId());
            } catch (TaskRejectedException exception) {
                log.debug("Material ingestion queue remains full; material {} stays pending", material.getId());
                break;
            }
        }
    }
}
