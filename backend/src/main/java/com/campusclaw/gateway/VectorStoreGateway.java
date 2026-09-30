package com.campusclaw.gateway;

import java.util.List;

public interface VectorStoreGateway {
    void ensureCollection();
    void upsert(List<VectorPoint> points);
    List<VectorMatch> search(List<Double> vector, Long classId, int limit, double threshold);
    void deletePoints(List<Long> pointIds);
    void deleteMaterial(Long materialId);

    record VectorPoint(Long id, List<Double> vector, Long classId, Long materialId,
                       Long knowledgeEntryId, int chunkIndex) {
    }

    record VectorMatch(Long chunkId, double score) {
    }
}
