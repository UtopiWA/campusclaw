package com.campusclaw.material;

import java.util.List;

/** 返回被引用切片及相邻切片，供材料页可靠定位而不依赖摘录字符串匹配。 */
public record MaterialChunkContext(Long materialId, String materialTitle, Long chunkId,
                                   int chunkIndex, int startOffset, int endOffset,
                                   String excerpt, List<Neighbor> neighbors) {
    public record Neighbor(Long chunkId, int chunkIndex, String excerpt, boolean target) {
    }
}
