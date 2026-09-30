package com.campusclaw.material;

import com.campusclaw.knowledge.BreakPreference;
import com.campusclaw.knowledge.ChunkingOptions;
import com.campusclaw.persistence.IndexStrategy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RebuildIndexRequest(
        @NotNull IndexStrategy strategy,
        @Min(100) @Max(2000) Integer maxCodePoints,
        @Min(0) @Max(50) Integer overlapPercent,
        BreakPreference breakPreference,
        boolean preprocess) {

    public ChunkingOptions toOptions() {
        return new ChunkingOptions(strategy, maxCodePoints, overlapPercent, breakPreference, preprocess).validated();
    }
}
