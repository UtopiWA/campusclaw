package com.campusclaw.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.gateway.EmbeddingGateway;
import com.campusclaw.gateway.VectorStoreGateway;
import com.campusclaw.gateway.VectorStoreGateway.VectorPoint;
import com.campusclaw.material.MaterialView;
import com.campusclaw.persistence.KnowledgeChunk;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@SuppressWarnings("unchecked")
class IndexingServiceTest {
    private final IndexStateService state = mock(IndexStateService.class);
    private final ChunkingService chunking = mock(ChunkingService.class);
    private final EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
    private final VectorStoreGateway vectors = mock(VectorStoreGateway.class);
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final IndexingService service = new IndexingService(state, chunking, embeddings, vectors, materials);
    private final Material material = mock(Material.class);
    private final KnowledgeChunk pending = mock(KnowledgeChunk.class);

    @BeforeEach
    void prepareSuccessfulDraft() {
        when(material.getId()).thenReturn(7L);
        when(material.getClassId()).thenReturn(2L);
        when(state.require(7L, 2L)).thenReturn(material);
        when(state.sourceEntries(7L, 2L)).thenReturn(List.of(
                new ChunkingService.SourceEntry(11L, "正文")));
        when(state.existingPointIds(7L, 2L)).thenReturn(List.of(90L));
        when(chunking.chunkEntries(any(), any())).thenReturn(List.of(
                new ChunkDraft(11L, 0, "正文", 0, 2)));
        when(pending.getId()).thenReturn(101L);
        when(pending.getKnowledgeEntryId()).thenReturn(11L);
        when(pending.getChunkIndex()).thenReturn(0);
        when(pending.getChunkText()).thenReturn("正文");
        when(state.replaceWithPending(7L, 2L, List.of(
                new ChunkDraft(11L, 0, "正文", 0, 2)))).thenReturn(List.of(pending));
        when(embeddings.embed(List.of("正文"))).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
    }

    @Test
    void persistsDatabaseIdsBeforeUpsertAndMarksReadyAfterVectorWrite() {
        service.rebuild(7L, 2L, ChunkingOptions.auto());

        InOrder order = inOrder(state, vectors, embeddings);
        order.verify(state).begin(7L, 2L, com.campusclaw.persistence.IndexStrategy.AUTO);
        order.verify(vectors).deletePoints(List.of(90L));
        order.verify(state).replaceWithPending(7L, 2L, List.of(
                new ChunkDraft(11L, 0, "正文", 0, 2)));
        order.verify(embeddings).embed(List.of("正文"));
        ArgumentCaptor<List<VectorPoint>> points = ArgumentCaptor.forClass(List.class);
        order.verify(vectors).upsert(points.capture());
        order.verify(state).markReady(7L, 2L, List.of(101L));
        assertThat(points.getValue()).singleElement().satisfies(point -> {
            assertThat(point.id()).isEqualTo(101L);
            assertThat(point.classId()).isEqualTo(2L);
            assertThat(point.materialId()).isEqualTo(7L);
            assertThat(point.knowledgeEntryId()).isEqualTo(11L);
        });
    }

    @Test
    void dependencyFailureMarksPendingChunksFailedAndRemovesPossiblePartialPoints() {
        when(embeddings.embed(List.of("正文")))
                .thenThrow(new DependencyUnavailableException("Embedding service"));

        service.indexAfterCommit(7L, 2L, ChunkingOptions.auto());

        verify(vectors).deletePoints(List.of(90L));
        verify(vectors).deletePoints(List.of(101L));
        verify(state).markFailed(7L, 2L, List.of(101L));
        verify(vectors, never()).upsert(any());
    }

    @Test
    void backfillDoesNotReindexMaterialsOnceRepositoryReportsThemReady() {
        when(materials.findAllByIndexStatusNotOrderById(com.campusclaw.persistence.IndexStatus.READY))
                .thenReturn(List.of(material), List.of());
        IndexingService backfill = spy(new IndexingService(state, chunking, embeddings, vectors, materials));
        doReturn(mock(MaterialView.class)).when(backfill)
                .indexAfterCommit(7L, 2L, ChunkingOptions.auto());

        backfill.backfill();
        backfill.backfill();

        verify(backfill, times(1)).indexAfterCommit(7L, 2L, ChunkingOptions.auto());
    }
}
