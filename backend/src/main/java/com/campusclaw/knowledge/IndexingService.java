package com.campusclaw.knowledge;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.gateway.EmbeddingGateway;
import com.campusclaw.gateway.VectorStoreGateway;
import com.campusclaw.gateway.VectorStoreGateway.VectorPoint;
import com.campusclaw.material.MaterialView;
import com.campusclaw.persistence.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 编排切分、嵌入和向量写入；失败只改变索引状态，不回滚已提交的材料。 */
@Service
public class IndexingService {
    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final IndexStateService state;
    private final ChunkingService chunking;
    private final EmbeddingGateway embeddings;
    private final VectorStoreGateway vectors;

    public IndexingService(IndexStateService state, ChunkingService chunking, EmbeddingGateway embeddings,
                           VectorStoreGateway vectors) {
        this.state = state;
        this.chunking = chunking;
        this.embeddings = embeddings;
        this.vectors = vectors;
    }

    public MaterialView indexAfterCommit(Long materialId, Long classId, ChunkingOptions options) {
        try {
            rebuild(materialId, classId, options);
        } catch (RuntimeException exception) {
            log.warn("Material {} index build failed; material remains available", materialId);
        }
        return MaterialView.from(state.require(materialId, classId));
    }

    public MaterialView rebuild(Long materialId, Long classId, ChunkingOptions requested) {
        ChunkingOptions options = requested.validated();
        state.require(materialId, classId);
        List<ChunkingService.SourceEntry> sourceEntries = state.sourceEntries(materialId, classId);
        if (sourceEntries.isEmpty()) {
            throw new BadRequestException("Material has not been parsed; retry processing first");
        }
        List<ChunkDraft> drafts = chunking.chunkEntries(sourceEntries, options);
        List<Long> oldPointIds = state.existingPointIds(materialId, classId);
        state.begin(materialId, classId, options.strategy());
        List<Long> newPointIds = new ArrayList<>();
        try {
            vectors.deletePoints(oldPointIds);
            List<KnowledgeChunk> pending = state.replaceWithPending(materialId, classId, drafts);
            newPointIds.addAll(pending.stream().map(KnowledgeChunk::getId).toList());
            List<List<Double>> values = embeddings.embed(pending.stream().map(KnowledgeChunk::getChunkText).toList());
            List<VectorPoint> points = new ArrayList<>();
            for (int index = 0; index < pending.size(); index++) {
                KnowledgeChunk chunk = pending.get(index);
                points.add(new VectorPoint(chunk.getId(), values.get(index), classId, materialId,
                        chunk.getKnowledgeEntryId(), chunk.getChunkIndex()));
            }
            vectors.upsert(points);
            state.markReady(materialId, classId, newPointIds);
            return MaterialView.from(state.require(materialId, classId));
        } catch (RuntimeException exception) {
            try {
                vectors.deletePoints(newPointIds);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            state.markFailed(materialId, classId, newPointIds, exception);
            throw exception;
        }
    }
}
