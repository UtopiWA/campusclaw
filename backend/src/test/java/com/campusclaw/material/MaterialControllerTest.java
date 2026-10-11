package com.campusclaw.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusclaw.auth.CurrentUserService;
import com.campusclaw.knowledge.IndexingService;
import com.campusclaw.knowledge.MaterialIngestionService;
import com.campusclaw.knowledge.VectorCleanupService;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.IndexStrategy;
import com.campusclaw.persistence.UserAccount;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class MaterialControllerTest {

    @Test
    void uploadReturnsPendingMaterialWithoutRunningIndexingInRequestThread() {
        MaterialService materials = mock(MaterialService.class);
        CurrentUserService currentUsers = mock(CurrentUserService.class);
        IndexingService indexing = mock(IndexingService.class);
        MaterialIngestionService ingestion = mock(MaterialIngestionService.class);
        VectorCleanupService cleanup = mock(VectorCleanupService.class);
        UserAccount teacher = mock(UserAccount.class);
        when(teacher.getClassId()).thenReturn(2L);
        when(currentUsers.requireTeacher()).thenReturn(teacher);
        MaterialView pending = new MaterialView(7L, "讲义", "lesson.md", 12L, true, 3L,
                IndexStatus.PENDING, IndexStrategy.AUTO, null, Instant.now(), Instant.now());
        MockMultipartFile file = new MockMultipartFile(
                "file", "lesson.md", "text/markdown", "正文".getBytes(StandardCharsets.UTF_8));
        when(materials.upload(file, teacher)).thenReturn(pending);
        MaterialController controller = new MaterialController(
                materials, currentUsers, indexing, ingestion, cleanup);

        Map<String, Object> response = controller.upload(file);

        assertThat(response).containsEntry("materialId", 7L).containsEntry("material", pending);
        verify(ingestion).ingestAsync(7L, 2L);
        verifyNoInteractions(indexing);
    }
}
