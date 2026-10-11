package com.campusclaw.knowledge;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.campusclaw.material.MaterialService;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.IndexStrategy;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MaterialIngestionServiceTest {
    private final MaterialService materials = mock(MaterialService.class);
    private final IndexStateService state = mock(IndexStateService.class);
    private final IndexingService indexing = mock(IndexingService.class);
    private final MaterialRepository repository = mock(MaterialRepository.class);
    private final MaterialIngestionService service = new MaterialIngestionService(
            materials, state, indexing, repository);

    @Test
    void parsesCommittedFileBeforeBuildingItsIndex() {
        service.ingestNow(7L, 2L);

        InOrder order = inOrder(state, materials, indexing);
        order.verify(state).begin(7L, 2L, IndexStrategy.AUTO);
        order.verify(materials).parseStoredMaterial(7L, 2L);
        order.verify(indexing).indexAfterCommit(7L, 2L, ChunkingOptions.auto());
    }

    @Test
    void parsingFailureMarksMaterialFailedAndSkipsIndexing() {
        doThrow(new IllegalArgumentException("invalid UTF-8"))
                .when(materials).parseStoredMaterial(7L, 2L);

        service.ingestNow(7L, 2L);

        verify(state).markMaterialFailed(7L, 2L, "Material parsing or indexing failed");
        verify(indexing, never()).indexAfterCommit(7L, 2L, ChunkingOptions.auto());
    }

    @Test
    void startupBackfillUsesPersistedNonReadyMaterials() {
        Material material = mock(Material.class);
        when(material.getId()).thenReturn(7L);
        when(material.getClassId()).thenReturn(2L);
        when(repository.findAllByIndexStatusNotOrderById(IndexStatus.READY)).thenReturn(List.of(material));

        service.backfill();

        verify(materials).parseStoredMaterial(7L, 2L);
        verify(indexing).indexAfterCommit(7L, 2L, ChunkingOptions.auto());
    }
}
