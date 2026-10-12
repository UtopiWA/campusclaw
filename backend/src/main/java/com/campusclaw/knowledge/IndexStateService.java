package com.campusclaw.knowledge;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.material.MaterialView;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.IndexStrategy;
import com.campusclaw.persistence.KnowledgeChunk;
import com.campusclaw.persistence.KnowledgeChunkRepository;
import com.campusclaw.persistence.KnowledgeEntryRepository;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import com.campusclaw.persistence.ProcessingFailureCode;
import com.campusclaw.persistence.ProcessingStage;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 以独立短事务维护索引状态，外部 HTTP 调用不会占用数据库事务。 */
@Service
public class IndexStateService {
    private final MaterialRepository materials;
    private final KnowledgeEntryRepository entries;
    private final KnowledgeChunkRepository chunks;

    public IndexStateService(MaterialRepository materials, KnowledgeEntryRepository entries,
                             KnowledgeChunkRepository chunks) {
        this.materials = materials;
        this.entries = entries;
        this.chunks = chunks;
    }

    @Transactional(readOnly = true)
    public Material require(Long materialId, Long classId) {
        return materials.findByIdAndClassId(materialId, classId).orElseThrow(NotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<Long> existingPointIds(Long materialId, Long classId) {
        return chunks.findAllByMaterialIdAndClassIdOrderByChunkIndex(materialId, classId).stream()
                .map(KnowledgeChunk::getId).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPending(Long materialId, Long classId) {
        return materials.claimPending(materialId, classId, Instant.now()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requeueStale(Long materialId, Long classId, Instant staleBefore) {
        return materials.requeueStale(materialId, classId, staleBefore, Instant.now()) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void begin(Long materialId, Long classId, IndexStrategy strategy) {
        Material material = require(materialId, classId);
        material.markIndexing(strategy);
        materials.saveAndFlush(material);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<KnowledgeChunk> replaceWithPending(Long materialId, Long classId, List<ChunkDraft> drafts) {
        require(materialId, classId);
        chunks.deleteAllByMaterialId(materialId);
        chunks.flush();
        List<KnowledgeChunk> pending = drafts.stream()
                .map(draft -> new KnowledgeChunk(materialId, draft.sourceEntryId(), classId, draft.chunkIndex(),
                        draft.text(), draft.startOffset(), draft.endOffset()))
                .toList();
        return chunks.saveAllAndFlush(pending);
    }

    @Transactional(readOnly = true)
    public List<ChunkingService.SourceEntry> sourceEntries(Long materialId, Long classId) {
        return entries.findAllByMaterialIdAndClassIdOrderByChunkIndex(materialId, classId).stream()
                .map(entry -> new ChunkingService.SourceEntry(entry.getId(), entry.getBodyText()))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(Long materialId, Long classId, List<Long> chunkIds) {
        Material material = require(materialId, classId);
        List<KnowledgeChunk> ready = chunks.findAllByIdInAndClassIdAndIndexStatus(
                chunkIds, classId, IndexStatus.PENDING);
        ready.forEach(KnowledgeChunk::markReady);
        chunks.saveAll(ready);
        material.markReady();
        materials.saveAndFlush(material);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long materialId, Long classId, List<Long> chunkIds, RuntimeException cause) {
        boolean dependencyFailure = cause instanceof DependencyUnavailableException;
        ProcessingFailureCode code = dependencyFailure
                ? ProcessingFailureCode.DEPENDENCY_UNAVAILABLE
                : ProcessingFailureCode.PROCESSING_ERROR;
        materials.findByIdAndClassId(materialId, classId).ifPresent(material -> {
            material.markFailed(ProcessingStage.INDEXING, code, true, "Indexing failed");
            materials.save(material);
        });
        chunks.findAllByIdInAndClassIdAndIndexStatus(chunkIds, classId, IndexStatus.PENDING)
                .forEach(chunk -> chunk.markFailed(
                        dependencyFailure ? "Index dependency unavailable" : "Indexing failed"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markMaterialFailed(Long materialId, Long classId, ProcessingStage stage,
                                   ProcessingFailureCode code, boolean retryable, String message) {
        materials.findByIdAndClassId(materialId, classId).ifPresent(material -> {
            material.markFailed(stage, code, retryable, message);
            materials.saveAndFlush(material);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MaterialView queueForRetry(Long materialId, Long classId) {
        Material material = require(materialId, classId);
        if (material.getIndexStatus() != IndexStatus.FAILED || !material.isRetryable()) {
            throw new BadRequestException("Material is not retryable");
        }
        material.queueForRetry();
        return MaterialView.from(materials.saveAndFlush(material));
    }
}
