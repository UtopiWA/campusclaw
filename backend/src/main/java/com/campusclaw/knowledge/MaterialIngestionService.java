package com.campusclaw.knowledge;

import com.campusclaw.material.MaterialService;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.IndexStrategy;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 在请求事务提交后解析原文件并建立索引，数据库状态用于失败反馈和启动恢复。 */
@Service
public class MaterialIngestionService {
    private static final Logger log = LoggerFactory.getLogger(MaterialIngestionService.class);

    private final MaterialService materials;
    private final IndexStateService state;
    private final IndexingService indexing;
    private final MaterialRepository materialRepository;

    public MaterialIngestionService(MaterialService materials, IndexStateService state,
                                    IndexingService indexing, MaterialRepository materialRepository) {
        this.materials = materials;
        this.state = state;
        this.indexing = indexing;
        this.materialRepository = materialRepository;
    }

    @Async("materialIngestionExecutor")
    public CompletableFuture<Void> ingestAsync(Long materialId, Long classId) {
        ingestNow(materialId, classId);
        return CompletableFuture.completedFuture(null);
    }

    /** 同步核心便于启动补偿和单元测试复用，异常只转化为材料失败状态。 */
    public void ingestNow(Long materialId, Long classId) {
        try {
            state.begin(materialId, classId, IndexStrategy.AUTO);
            materials.parseStoredMaterial(materialId, classId);
            indexing.indexAfterCommit(materialId, classId, ChunkingOptions.auto());
        } catch (RuntimeException exception) {
            state.markMaterialFailed(materialId, classId, "Material parsing or indexing failed");
            log.warn("Material {} background ingestion failed", materialId);
        }
    }

    /** 进程中断不会丢失任务，非 READY 材料会在启动阶段按数据库状态重新处理。 */
    public void backfill() {
        for (Material material : materialRepository.findAllByIndexStatusNotOrderById(IndexStatus.READY)) {
            ingestNow(material.getId(), material.getClassId());
        }
    }
}
