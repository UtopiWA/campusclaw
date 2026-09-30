package com.campusclaw.knowledge;

import com.campusclaw.common.NotFoundException;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.IndexStrategy;
import com.campusclaw.persistence.KnowledgeChunk;
import com.campusclaw.persistence.KnowledgeChunkRepository;
import com.campusclaw.persistence.KnowledgeEntryRepository;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
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
    public void markFailed(Long materialId, Long classId, List<Long> chunkIds) {
        materials.findByIdAndClassId(materialId, classId).ifPresent(material -> {
            material.markFailed("Index dependency unavailable");
            materials.save(material);
        });
        chunks.findAllByIdInAndClassIdAndIndexStatus(chunkIds, classId, IndexStatus.PENDING)
                .forEach(chunk -> chunk.markFailed("Index dependency unavailable"));
    }
}
