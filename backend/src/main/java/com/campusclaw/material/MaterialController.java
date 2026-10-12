package com.campusclaw.material;

import com.campusclaw.auth.CurrentUserService;
import com.campusclaw.knowledge.IndexingService;
import com.campusclaw.knowledge.MaterialIngestionService;
import com.campusclaw.knowledge.VectorCleanupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** HTTP endpoints for class-scoped material metadata and original file transfer. */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    private static final Logger log = LoggerFactory.getLogger(MaterialController.class);

    private final MaterialService materials;
    private final CurrentUserService currentUsers;
    private final IndexingService indexing;
    private final MaterialIngestionService ingestion;
    private final VectorCleanupService vectorCleanup;

    public MaterialController(MaterialService materials, CurrentUserService currentUsers,
                              IndexingService indexing, MaterialIngestionService ingestion,
                              VectorCleanupService vectorCleanup) {
        this.materials = materials;
        this.currentUsers = currentUsers;
        this.indexing = indexing;
        this.ingestion = ingestion;
        this.vectorCleanup = vectorCleanup;
    }

    @GetMapping
    public List<MaterialView> list() {
        return materials.list(currentUsers.requireUser());
    }

    @GetMapping("/{id}")
    public MaterialView get(@PathVariable Long id) {
        return materials.get(id, currentUsers.requireUser());
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        return fileResponse(id, false);
    }

    @GetMapping("/{id}/chunks/{chunkId}/context")
    public MaterialChunkContext chunkContext(@PathVariable Long id, @PathVariable Long chunkId) {
        return materials.readChunkContext(id, chunkId, currentUsers.requireUser());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return fileResponse(id, true);
    }

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file) {
        var teacher = currentUsers.requireTeacher();
        MaterialView stored = materials.upload(file, teacher);
        try {
            // upload() 返回时主事务已提交，后台线程只携带受控标识重新加载数据。
            ingestion.ingestAsync(stored.id(), teacher.getClassId());
        } catch (TaskRejectedException exception) {
            // PENDING 状态会由后台恢复调度重新提交，不把已成功保存的材料误报为上传失败。
            log.warn("Material {} ingestion queue is full; leaving it pending", stored.id());
        }
        return Map.of("materialId", stored.id(), "material", stored);
    }

    @PostMapping("/{id}/index/rebuild")
    public MaterialView rebuildIndex(@PathVariable Long id, @Valid @RequestBody RebuildIndexRequest request) {
        var teacher = currentUsers.requireTeacher();
        return indexing.rebuild(id, teacher.getClassId(), request.toOptions());
    }

    @PostMapping("/{id}/processing/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialView retryProcessing(@PathVariable Long id) {
        var teacher = currentUsers.requireTeacher();
        MaterialView queued = ingestion.prepareRetry(id, teacher.getClassId());
        try {
            ingestion.ingestAsync(id, teacher.getClassId());
        } catch (TaskRejectedException exception) {
            log.warn("Material {} retry queue is full; leaving it pending", id);
        }
        return queued;
    }

    @PatchMapping("/{id}")
    public MaterialView update(@PathVariable Long id, @Valid @RequestBody UpdateMaterialRequest request) {
        return materials.update(id, request.title(), currentUsers.requireTeacher());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        var teacher = currentUsers.requireTeacher();
        materials.delete(id, teacher);
        vectorCleanup.processMaterial(id);
    }

    private ResponseEntity<byte[]> fileResponse(Long id, boolean download) {
        // Preview and download share the same authorization path; only Content-Disposition differs.
        MaterialService.MaterialFile file = materials.readFile(id, currentUsers.requireUser());
        String filename = file.filename() == null || file.filename().isBlank() ? "material.txt" : file.filename();
        ContentDisposition disposition = download
                ? ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build();
        // Markdown is intentionally served as plain text so the browser never executes uploaded content.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .contentLength(file.content().length)
                .body(file.content());
    }

    public record UpdateMaterialRequest(@NotBlank @Size(max = 255) String title) {
    }
}
