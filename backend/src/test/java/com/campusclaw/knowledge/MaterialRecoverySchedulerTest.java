package com.campusclaw.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialRecoverySchedulerTest {
    @Test
    void requeuesStaleWorkAndSubmitsPendingMaterialsWithoutProcessingInline() {
        MaterialRepository repository = mock(MaterialRepository.class);
        IndexStateService state = mock(IndexStateService.class);
        MaterialIngestionService ingestion = mock(MaterialIngestionService.class);
        Material stale = material(7L, 2L);
        Material pending = material(8L, 2L);
        when(repository.findTop20ByIndexStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(IndexStatus.INDEXING), any())).thenReturn(List.of(stale));
        when(repository.findTop20ByIndexStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(IndexStatus.PENDING), any())).thenReturn(List.of(pending));
        when(state.requeueStale(eq(7L), eq(2L), any())).thenReturn(true);

        new MaterialRecoveryScheduler(repository, state, ingestion).recover();

        verify(ingestion).ingestAsync(7L, 2L);
        verify(ingestion).ingestAsync(8L, 2L);
    }

    private Material material(Long id, Long classId) {
        Material material = mock(Material.class);
        when(material.getId()).thenReturn(id);
        when(material.getClassId()).thenReturn(classId);
        return material;
    }
}
