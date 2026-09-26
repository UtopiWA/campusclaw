package com.campusclaw.material;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.common.StorageException;
import com.campusclaw.config.AppProperties;
import com.campusclaw.knowledge.TextParser;
import com.campusclaw.persistence.KnowledgeEntry;
import com.campusclaw.persistence.KnowledgeEntryRepository;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import com.campusclaw.persistence.UserAccount;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Coordinates material metadata, parsed knowledge entries and files on disk while enforcing class isolation.
 * Filesystem changes are paired with transaction callbacks so a database rollback does not leave orphaned files.
 */
@Service
public class MaterialService {
    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md");

    private final MaterialRepository materials;
    private final KnowledgeEntryRepository knowledgeEntries;
    private final TextParser textParser;
    private final Path uploadRoot;
    private final long maxUploadBytes;

    public MaterialService(MaterialRepository materials,
                           KnowledgeEntryRepository knowledgeEntries,
                           TextParser textParser,
                           AppProperties properties) {
        this.materials = materials;
        this.knowledgeEntries = knowledgeEntries;
        this.textParser = textParser;
        this.uploadRoot = Path.of(properties.uploadDir()).toAbsolutePath().normalize();
        this.maxUploadBytes = properties.maxUploadBytes();
    }

    @Transactional(readOnly = true)
    public List<MaterialView> list(UserAccount user) {
        return materials.findAllByClassIdOrderByCreatedAtDesc(user.getClassId()).stream()
                .map(MaterialView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MaterialView get(Long id, UserAccount user) {
        return MaterialView.from(requireMaterial(id, user.getClassId()));
    }

    @Transactional(readOnly = true)
    public MaterialFile readFile(Long id, UserAccount user) {
        Material material = requireMaterial(id, user.getClassId());
        // Resolve the persisted relative path inside the current class directory before touching the filesystem.
        Path storedFile = resolveStoredPath(material.getStoredPath(), user.getClassId());
        if (storedFile == null || !Files.isRegularFile(storedFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotFoundException();
        }
        try {
            return new MaterialFile(material.getOriginalFilename(), Files.readAllBytes(storedFile));
        } catch (IOException exception) {
            throw new StorageException("Could not read material file", exception);
        }
    }

    @Transactional
    public MaterialView update(Long id, String title, UserAccount teacher) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isBlank() || cleanTitle.length() > 255) {
            throw new BadRequestException("Title must contain 1 to 255 characters");
        }
        Material material = requireMaterial(id, teacher.getClassId());
        material.setTitle(cleanTitle);
        return MaterialView.from(materials.saveAndFlush(material));
    }

    @Transactional
    public MaterialView upload(MultipartFile file, UserAccount teacher) {
        UploadContent content = validateAndRead(file);
        List<String> chunks = textParser.parse(content.bytes());
        Path temp = null;
        Path destination = null;
        try {
            // Stage bytes first; the final class-scoped name is not exposed until parsing and persistence succeed.
            Path tempDir = uploadRoot.resolve(".tmp");
            Path classDir = uploadRoot.resolve(teacher.getClassId().toString());
            Files.createDirectories(tempDir);
            Files.createDirectories(classDir);
            temp = Files.createTempFile(tempDir, "upload-", ".tmp");
            Files.write(temp, content.bytes());

            String storedName = UUID.randomUUID() + "." + content.extension();
            destination = classDir.resolve(storedName).normalize();
            requireWithinRoot(destination);

            // Material metadata and searchable chunks share one database transaction.
            Material material = materials.saveAndFlush(new Material(
                    teacher.getClassId(), content.title(), content.originalFilename(), null, teacher.getId()));
            for (int index = 0; index < chunks.size(); index++) {
                knowledgeEntries.save(new KnowledgeEntry(material.getId(), teacher.getClassId(), index, chunks.get(index)));
            }
            knowledgeEntries.flush();

            // Publish the file atomically, then register rollback cleanup for the remaining transaction lifetime.
            moveAtomically(temp, destination);
            material.setStoredPath(toStoredPath(destination));
            material.setFileSizeBytes((long) content.bytes().length);
            materials.saveAndFlush(material);
            registerUploadCleanup(temp, destination);
            return MaterialView.from(material);
        } catch (IOException exception) {
            deleteQuietly(temp);
            deleteQuietly(destination);
            throw new StorageException("Could not store uploaded file", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(temp);
            deleteQuietly(destination);
            throw exception;
        }
    }

    @Transactional
    public void delete(Long id, UserAccount teacher) {
        Material material = requireMaterial(id, teacher.getClassId());
        Path original = resolveStoredPath(material.getStoredPath());
        Path quarantined = null;
        try {
            if (original != null && Files.exists(original)) {
                // Quarantine first so the file can be restored if the following database delete rolls back.
                Path trashDir = uploadRoot.resolve(".trash");
                Files.createDirectories(trashDir);
                quarantined = trashDir.resolve(UUID.randomUUID() + ".deleted");
                moveAtomically(original, quarantined);
                registerDeleteCompensation(original, quarantined);
            }
            materials.delete(material);
            materials.flush();
        } catch (IOException exception) {
            restoreQuietly(quarantined, original);
            throw new StorageException("Could not delete material file", exception);
        } catch (RuntimeException exception) {
            restoreQuietly(quarantined, original);
            throw exception;
        }
    }

    private Material requireMaterial(Long id, Long classId) {
        // The combined lookup deliberately returns 404 for both missing and cross-class records.
        return materials.findByIdAndClassId(id, classId).orElseThrow(NotFoundException::new);
    }

    private UploadContent validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File must not be empty");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new BadRequestException("File exceeds the configured size limit");
        }
        String original = safeOriginalFilename(file.getOriginalFilename());
        int dot = original.lastIndexOf('.');
        if (dot <= 0 || dot == original.length() - 1) {
            throw new BadRequestException("Only .txt and .md files are supported");
        }
        String extension = original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Only .txt and .md files are supported");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw new BadRequestException("File must not be empty");
            }
            String title = original.substring(0, dot).trim();
            if (title.isBlank()) {
                title = "未命名材料";
            }
            return new UploadContent(original, title, extension, bytes);
        } catch (IOException exception) {
            throw new BadRequestException("Could not read uploaded file", exception);
        }
    }

    private String safeOriginalFilename(String value) {
        // Browsers may submit a client-side path; retain only the basename and reject unsafe control characters.
        String candidate = value == null ? "" : value.replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        candidate = slash >= 0 ? candidate.substring(slash + 1) : candidate;
        if (candidate.isBlank() || candidate.length() > 255
                || candidate.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("Invalid original filename");
        }
        return candidate;
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Upload directory must support atomic moves", exception);
        }
    }

    private String toStoredPath(Path path) {
        return uploadRoot.relativize(path).toString().replace('\\', '/');
    }

    private Path resolveStoredPath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        Path resolved = uploadRoot.resolve(storedPath).normalize();
        requireWithinRoot(resolved);
        return resolved;
    }

    private Path resolveStoredPath(String storedPath, Long classId) {
        Path resolved = resolveStoredPath(storedPath);
        if (resolved == null) {
            return null;
        }
        // A valid upload-root path is still rejected when it belongs to another class directory.
        Path classRoot = uploadRoot.resolve(classId.toString()).normalize();
        if (!resolved.startsWith(classRoot)) {
            throw new NotFoundException();
        }
        return resolved;
    }

    private void requireWithinRoot(Path path) {
        if (!path.startsWith(uploadRoot)) {
            throw new BadRequestException("Invalid storage path");
        }
    }

    private void registerUploadCleanup(Path temp, Path destination) {
        // Database rollback must remove a file that was already atomically published.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                deleteQuietly(temp);
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(destination);
                }
            }
        });
    }

    private void registerDeleteCompensation(Path original, Path quarantined) {
        // Commit makes quarantine permanent; rollback restores the original file in place.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    deleteQuietly(quarantined);
                } else {
                    restoreQuietly(quarantined, original);
                }
            }
        });
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Could not clean up file {}", path, exception);
        }
    }

    private void restoreQuietly(Path source, Path destination) {
        if (source == null || destination == null || !Files.exists(source)) {
            return;
        }
        try {
            Files.createDirectories(destination.getParent());
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            log.error("Could not restore quarantined file {}", source, exception);
        }
    }

    private record UploadContent(String originalFilename, String title, String extension, byte[] bytes) {
    }

    public record MaterialFile(String filename, byte[] content) {
    }
}
