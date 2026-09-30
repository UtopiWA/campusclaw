package com.campusclaw.material;

import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.IndexStrategy;
import java.time.Instant;

public record MaterialView(Long id, String title, String originalFilename, Long fileSizeBytes,
                           boolean hasFile, Long uploadedBy, IndexStatus indexStatus,
                           IndexStrategy indexStrategy, Instant indexedAt,
                           Instant createdAt, Instant updatedAt) {
    public static MaterialView from(Material material) {
        return new MaterialView(
                material.getId(),
                material.getTitle(),
                material.getOriginalFilename(),
                material.getFileSizeBytes(),
                material.getStoredPath() != null && !material.getStoredPath().isBlank(),
                material.getUploadedBy(),
                material.getIndexStatus(),
                material.getIndexStrategy(),
                material.getIndexedAt(),
                material.getCreatedAt(),
                material.getUpdatedAt());
    }
}
