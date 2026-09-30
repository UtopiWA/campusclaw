package com.campusclaw.knowledge;

/** 偏移量以预处理后来源条目的 Unicode code point 半开区间表示。 */
public record ChunkDraft(
        Long sourceEntryId,
        int chunkIndex,
        String text,
        int startOffset,
        int endOffset) {
}
