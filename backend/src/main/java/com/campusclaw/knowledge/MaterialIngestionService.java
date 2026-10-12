package com.campusclaw.knowledge;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.material.MaterialView;
import com.campusclaw.material.MaterialService;
import com.campusclaw.persistence.ProcessingFailureCode;
import com.campusclaw.persistence.ProcessingStage;
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

    public MaterialIngestionService(MaterialService materials, IndexStateService state,
                                    IndexingService indexing) {
        this.materials = materials;
        this.state = state;
        this.indexing = indexing;
    }

    @Async("materialIngestionExecutor")
    public CompletableFuture<Void> ingestAsync(Long materialId, Long classId) {
        ingestNow(materialId, classId);
        return CompletableFuture.completedFuture(null);
    }

    /** 同步核心先原子认领材料；重复调度未认领成功时直接安全结束。 */
    public void ingestNow(Long materialId, Long classId) {
        if (!state.claimPending(materialId, classId)) {
            return;
        }
        try {
            materials.parseStoredMaterial(materialId, classId);
            indexing.indexAfterCommit(materialId, classId, ChunkingOptions.auto());
        } catch (BadRequestException exception) {
            state.markMaterialFailed(materialId, classId, ProcessingStage.PARSING,
                    ProcessingFailureCode.INVALID_TEXT, false, "Invalid UTF-8 text material");
            log.warn("Material {} contains invalid text and will not be retried automatically", materialId);
        } catch (RuntimeException exception) {
            state.markMaterialFailed(materialId, classId, ProcessingStage.PARSING,
                    ProcessingFailureCode.PROCESSING_ERROR, true, "Material parsing failed");
            log.warn("Material {} background parsing failed", materialId);
        }
    }

    public MaterialView prepareRetry(Long materialId, Long classId) {
        return state.queueForRetry(materialId, classId);
    }
}
