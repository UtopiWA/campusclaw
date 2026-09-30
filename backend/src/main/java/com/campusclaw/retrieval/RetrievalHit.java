package com.campusclaw.retrieval;

/** API 只暴露定位依据与适用评分，不暴露向量、存储路径或内部服务地址。 */
public record RetrievalHit(
        Long materialId,
        String materialTitle,
        Long knowledgeEntryId,
        Long chunkId,
        int chunkIndex,
        int startOffset,
        int endOffset,
        String excerpt,
        int rank,
        Double finalScore,
        Double keywordScore,
        Integer keywordRank,
        Double vectorScore,
        Integer vectorRank) {
}
