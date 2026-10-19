package com.campusclaw.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.config.AppProperties;
import com.campusclaw.gateway.EmbeddingGateway;
import com.campusclaw.gateway.VectorStoreGateway;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.KnowledgeChunk;
import com.campusclaw.persistence.KnowledgeChunkRepository;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {
    private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
    private final MaterialRepository materials = mock(MaterialRepository.class);
    private final EmbeddingGateway embeddings = mock(EmbeddingGateway.class);
    private final VectorStoreGateway vectors = mock(VectorStoreGateway.class);
    private final RetrievalService service = new RetrievalService(chunks, materials, embeddings, vectors, properties());

    @Test
    void rejectsInvalidQueryBeforeCallingDependencies() {
        assertThatThrownBy(() -> service.search(new SearchRequest(" ", SearchMode.HYBRID, 10, 99L), 1L))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(new SearchRequest("问题", SearchMode.HYBRID, 21, null), 1L))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(embeddings, vectors);
    }

    @Test
    void keywordModeDoesNotCallVectorDependenciesAndReturnsFixedEmptyMessage() {
        when(chunks.searchReady(1L, "不存在", 30)).thenReturn(List.of());
        SearchResponse response = service.search(new SearchRequest("不存在", SearchMode.KEYWORD, null, 2L), 1L);
        assertThat(response.hits()).isEmpty();
        assertThat(response.message()).isEqualTo(RetrievalService.NO_EVIDENCE);
        verifyNoInteractions(embeddings, vectors);
    }

    @Test
    void vectorModeUsesSessionClassAndDropsCandidatesRejectedByReadyBackfill() {
        KnowledgeChunk ready = readyChunk(1L, 1L, 8L);
        stubReadyRows(List.of(ready));
        when(embeddings.embed(List.of("同义改写"))).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        when(vectors.search(List.of(0.1, 0.2, 0.3), 1L, 30, 0.35)).thenReturn(List.of(
                new VectorStoreGateway.VectorMatch(99L, 0.9),
                new VectorStoreGateway.VectorMatch(1L, 0.8)));

        SearchResponse response = service.search(
                new SearchRequest("同义改写", SearchMode.VECTOR, 10, 999L), 1L);

        assertThat(response.hits()).extracting(RetrievalHit::chunkId).containsExactly(1L);
        assertThat(response.hits().get(0).vectorRank()).isEqualTo(2);
        verify(vectors).search(List.of(0.1, 0.2, 0.3), 1L, 30, 0.35);
        verify(chunks, org.mockito.Mockito.atLeastOnce())
                .findAllByIdInAndClassIdAndIndexStatus(any(), eq(1L), eq(IndexStatus.READY));
    }

    @Test
    void omittedModeRunsBothRoutesAndUsesRrfScore() {
        KnowledgeChunk ready = readyChunk(1L, 1L, 8L);
        stubReadyRows(List.of(ready));
        KnowledgeChunkRepository.KeywordHitProjection keyword = mock(
                KnowledgeChunkRepository.KeywordHitProjection.class);
        when(keyword.getId()).thenReturn(1L);
        when(keyword.getKeywordScore()).thenReturn(9.5);
        when(chunks.searchReady(1L, "课堂设计", 30)).thenReturn(List.of(keyword));
        when(embeddings.embed(List.of("课堂设计"))).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        when(vectors.search(List.of(0.1, 0.2, 0.3), 1L, 30, 0.35))
                .thenReturn(List.of(new VectorStoreGateway.VectorMatch(1L, 0.8)));

        SearchResponse response = service.search(new SearchRequest("课堂设计", null, null, null), 1L);

        assertThat(response.mode()).isEqualTo(SearchMode.HYBRID);
        assertThat(response.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.finalScore()).isEqualTo(2.0 / 61);
            assertThat(hit.keywordScore()).isEqualTo(9.5);
            assertThat(hit.vectorScore()).isEqualTo(0.8);
        });
    }

    @Test
    void boundSearchShortCircuitsEmptyWhitelistWithoutLookingAtClassMaterials() {
        SearchResponse response = service.searchBound("任意问题", 1L, Set.of(), 4);

        assertThat(response.hits()).isEmpty();
        assertThat(response.message()).isEqualTo(RetrievalService.NO_EVIDENCE);
        verifyNoInteractions(chunks, materials, embeddings, vectors);
    }

    @Test
    void boundSearchFiltersKeywordQueryAndVectorBackfillByAllowedMaterials() {
        KnowledgeChunk allowed = readyChunk(1L, 1L, 8L);
        when(chunks.findAllByIdInAndClassIdAndIndexStatusAndMaterialIdIn(
                any(), eq(1L), eq(IndexStatus.READY), eq(Set.of(8L))))
                .thenReturn(List.of(allowed));
        KnowledgeChunkRepository.KeywordHitProjection keyword = mock(
                KnowledgeChunkRepository.KeywordHitProjection.class);
        when(keyword.getId()).thenReturn(1L);
        when(keyword.getKeywordScore()).thenReturn(5.0);
        when(chunks.searchReadyWithinMaterials(1L, Set.of(8L), "课堂问题", 12))
                .thenReturn(List.of(keyword));
        when(embeddings.embed(List.of("课堂问题"))).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        when(vectors.search(List.of(0.1, 0.2, 0.3), 1L, 12, 0.35)).thenReturn(List.of(
                new VectorStoreGateway.VectorMatch(2L, 0.95),
                new VectorStoreGateway.VectorMatch(1L, 0.8)));
        Material material = mock(Material.class);
        when(material.getId()).thenReturn(8L);
        when(material.getClassId()).thenReturn(1L);
        when(material.getIndexStatus()).thenReturn(IndexStatus.READY);
        when(material.getTitle()).thenReturn("允许材料");
        when(materials.findAllById(any())).thenReturn(List.of(material));

        SearchResponse response = service.searchBound("课堂问题", 1L, Set.of(8L), 4);

        assertThat(response.hits()).extracting(RetrievalHit::chunkId).containsExactly(1L);
        verify(chunks).searchReadyWithinMaterials(1L, Set.of(8L), "课堂问题", 12);
        verify(chunks, org.mockito.Mockito.atLeastOnce())
                .findAllByIdInAndClassIdAndIndexStatusAndMaterialIdIn(
                        any(), eq(1L), eq(IndexStatus.READY), eq(Set.of(8L)));
    }

    @Test
    void answerSearchKeepsStrongVectorAndCorroboratedCandidatesButDirectSearchKeepsWideRecall() {
        List<KnowledgeChunk> ready = List.of(
                readyChunk(1L, 1L, 8L), readyChunk(2L, 1L, 8L),
                readyChunk(3L, 1L, 8L), readyChunk(4L, 1L, 8L));
        stubReadyRows(ready);
        List<KnowledgeChunkRepository.KeywordHitProjection> keywordHits = List.of(
                keywordHit(1L, 8.0), keywordHit(2L, 2.0), keywordHit(4L, 1.0));
        when(chunks.searchReady(1L, "边界问题", 12)).thenReturn(keywordHits);
        when(embeddings.embed(List.of("边界问题"))).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        when(vectors.search(List.of(0.1, 0.2, 0.3), 1L, 12, 0.35)).thenReturn(List.of(
                new VectorStoreGateway.VectorMatch(3L, 0.56),
                new VectorStoreGateway.VectorMatch(2L, 0.46),
                new VectorStoreGateway.VectorMatch(4L, 0.44)));

        SearchResponse answer = service.searchForAnswer("边界问题", 1L, 4);
        SearchResponse direct = service.search(new SearchRequest("边界问题", SearchMode.HYBRID, 4, null), 1L);

        assertThat(answer.hits()).extracting(RetrievalHit::chunkId).containsExactly(2L, 3L);
        assertThat(answer.hits()).extracting(RetrievalHit::rank).containsExactly(1, 2);
        assertThat(direct.hits()).extracting(RetrievalHit::chunkId)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    @Test
    void answerSearchReturnsNoEvidenceWhenOnlyKeywordOrWeakDualCandidatesExist() {
        List<KnowledgeChunk> ready = List.of(readyChunk(1L, 1L, 8L), readyChunk(2L, 1L, 8L));
        stubReadyRows(ready);
        List<KnowledgeChunkRepository.KeywordHitProjection> keywordHits = List.of(
                keywordHit(1L, 9.0), keywordHit(2L, 4.0));
        when(chunks.searchReady(1L, "无关问题", 12)).thenReturn(keywordHits);
        when(embeddings.embed(List.of("无关问题"))).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        when(vectors.search(List.of(0.1, 0.2, 0.3), 1L, 12, 0.35))
                .thenReturn(List.of(new VectorStoreGateway.VectorMatch(2L, 0.44)));

        SearchResponse response = service.searchForAnswer("无关问题", 1L, 4);

        assertThat(response.hits()).isEmpty();
        assertThat(response.message()).isEqualTo(RetrievalService.NO_EVIDENCE);
    }

    private KnowledgeChunkRepository.KeywordHitProjection keywordHit(Long id, double score) {
        KnowledgeChunkRepository.KeywordHitProjection hit = mock(
                KnowledgeChunkRepository.KeywordHitProjection.class);
        when(hit.getId()).thenReturn(id);
        when(hit.getKeywordScore()).thenReturn(score);
        return hit;
    }

    private KnowledgeChunk readyChunk(Long id, Long classId, Long materialId) {
        KnowledgeChunk chunk = mock(KnowledgeChunk.class);
        when(chunk.getId()).thenReturn(id);
        when(chunk.getClassId()).thenReturn(classId);
        when(chunk.getMaterialId()).thenReturn(materialId);
        when(chunk.getKnowledgeEntryId()).thenReturn(4L);
        when(chunk.getChunkIndex()).thenReturn(0);
        when(chunk.getStartOffset()).thenReturn(0);
        when(chunk.getEndOffset()).thenReturn(4);
        when(chunk.getChunkText()).thenReturn("本班依据");
        return chunk;
    }

    private void stubReadyRows(List<KnowledgeChunk> ready) {
        when(chunks.findAllByIdInAndClassIdAndIndexStatus(any(), eq(1L), eq(IndexStatus.READY)))
                .thenReturn(ready);
        Material material = mock(Material.class);
        when(material.getId()).thenReturn(8L);
        when(material.getClassId()).thenReturn(1L);
        when(material.getIndexStatus()).thenReturn(IndexStatus.READY);
        when(material.getTitle()).thenReturn("本班讲义");
        when(materials.findAllById(any())).thenReturn(List.of(material));
    }

    private AppProperties properties() {
        return new AppProperties("./uploads", 1024, new AppProperties.DemoSeed(false, ""),
                new AppProperties.Qdrant("http://localhost", "test"),
                new AppProperties.Embedding("http://localhost/v1", "key", "embed", 3),
                new AppProperties.Chat("http://localhost/v1", "key", "chat", "none"),
                new AppProperties.Retrieval(Duration.ofSeconds(1), Duration.ofSeconds(2),
                        1000, 10, 20, 0.35, 0.55, 0.45, 60, false));
    }
}
