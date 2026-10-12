package com.campusclaw.knowledge;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.material.MaterialService;
import com.campusclaw.persistence.ProcessingFailureCode;
import com.campusclaw.persistence.ProcessingStage;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class MaterialIngestionServiceTest {
    private final MaterialService materials = mock(MaterialService.class);
    private final IndexStateService state = mock(IndexStateService.class);
    private final IndexingService indexing = mock(IndexingService.class);
    private final MaterialIngestionService service = new MaterialIngestionService(materials, state, indexing);

    @Test
    void claimsAndParsesCommittedFileBeforeBuildingItsIndex() {
        when(state.claimPending(7L, 2L)).thenReturn(true);

        service.ingestNow(7L, 2L);

        InOrder order = inOrder(state, materials, indexing);
        order.verify(state).claimPending(7L, 2L);
        order.verify(materials).parseStoredMaterial(7L, 2L);
        order.verify(indexing).indexAfterCommit(7L, 2L, ChunkingOptions.auto());
    }

    @Test
    void duplicateDispatchStopsWhenMaterialWasAlreadyClaimed() {
        when(state.claimPending(7L, 2L)).thenReturn(false);

        service.ingestNow(7L, 2L);

        verify(materials, never()).parseStoredMaterial(7L, 2L);
        verify(indexing, never()).indexAfterCommit(7L, 2L, ChunkingOptions.auto());
    }

    @Test
    void invalidTextIsPermanentAndSkipsIndexing() {
        when(state.claimPending(7L, 2L)).thenReturn(true);
        doThrow(new BadRequestException("File must be valid UTF-8"))
                .when(materials).parseStoredMaterial(7L, 2L);

        service.ingestNow(7L, 2L);

        verify(state).markMaterialFailed(7L, 2L, ProcessingStage.PARSING,
                ProcessingFailureCode.INVALID_TEXT, false, "Invalid UTF-8 text material");
        verify(indexing, never()).indexAfterCommit(7L, 2L, ChunkingOptions.auto());
    }

    @Test
    void transientParsingFailureCanBeRetried() {
        when(state.claimPending(7L, 2L)).thenReturn(true);
        doThrow(new IllegalStateException("temporary storage error"))
                .when(materials).parseStoredMaterial(7L, 2L);

        service.ingestNow(7L, 2L);

        verify(state).markMaterialFailed(7L, 2L, ProcessingStage.PARSING,
                ProcessingFailureCode.PROCESSING_ERROR, true, "Material parsing failed");
    }
}
