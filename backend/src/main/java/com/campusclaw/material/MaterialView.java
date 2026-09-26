package com.campusclaw.material;

import com.campusclaw.persistence.Material;
import java.time.Instant;

public record MaterialView(Long id, String title, String originalFilename, Long fileSizeBytes,
                           boolean hasFile, Long uploadedBy, Instant createdAt, Instant updatedAt) {
    static MaterialView from(Material material) {
        return new MaterialView(
                material.getId(),
                material.getTitle(),
                material.getOriginalFilename(),
                material.getFileSizeBytes(),
                material.getStoredPath() != null && !material.getStoredPath().isBlank(),
                material.getUploadedBy(),
                material.getCreatedAt(),
                material.getUpdatedAt());
    }
}
