package com.campusclaw.knowledge;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.persistence.IndexStrategy;

/** 描述一次可复现的切分策略；校验在删除旧索引之前执行。 */
public record ChunkingOptions(
        IndexStrategy strategy,
        Integer maxCodePoints,
        Integer overlapPercent,
        BreakPreference breakPreference,
        boolean preprocess) {

    public static ChunkingOptions auto() {
        return new ChunkingOptions(IndexStrategy.AUTO, 800, 10, BreakPreference.PARAGRAPH, false);
    }

    public static ChunkingOptions hierarchy() {
        return new ChunkingOptions(IndexStrategy.HIERARCHY, 800, 10, BreakPreference.PARAGRAPH, false);
    }

    public ChunkingOptions validated() {
        if (strategy == null) {
            throw new BadRequestException("Index strategy is required");
        }
        if (strategy == IndexStrategy.AUTO) {
            return auto();
        }
        if (strategy == IndexStrategy.HIERARCHY) {
            return hierarchy();
        }
        if (maxCodePoints == null || maxCodePoints < 100 || maxCodePoints > 2000) {
            throw new BadRequestException("Custom chunk size must be between 100 and 2000");
        }
        if (overlapPercent == null || overlapPercent < 0 || overlapPercent > 50) {
            throw new BadRequestException("Custom overlap must be between 0 and 50 percent");
        }
        if (breakPreference == null) {
            throw new BadRequestException("Custom break preference is required");
        }
        return this;
    }
}
