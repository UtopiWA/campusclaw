package com.campusclaw.retrieval;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.config.AppProperties;
import com.campusclaw.gateway.EmbeddingGateway;
import com.campusclaw.gateway.VectorStoreGateway;
import com.campusclaw.persistence.IndexStatus;
import com.campusclaw.persistence.KnowledgeChunk;
import com.campusclaw.persistence.KnowledgeChunkRepository;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以会话班级为唯一隔离来源，编排 keyword、vector 与 RRF hybrid 检索。 */
@Service
public class RetrievalService {
    public static final String NO_EVIDENCE = "资料中未找到相关内容";

    private final KnowledgeChunkRepository chunks;
    private final MaterialRepository materials;
    private final EmbeddingGateway embeddings;
    private final VectorStoreGateway vectors;
    private final AppProperties.Retrieval properties;

    public RetrievalService(KnowledgeChunkRepository chunks, MaterialRepository materials,
                            EmbeddingGateway embeddings, VectorStoreGateway vectors,
                            AppProperties appProperties) {
        this.chunks = chunks;
        this.materials = materials;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.properties = appProperties.retrieval();
    }

    @Transactional(readOnly = true)
    public SearchResponse search(SearchRequest request, Long sessionClassId) {
        return search(request, sessionClassId, null);
    }

    /** 智能体链路使用服务端材料白名单；空集合明确表示没有可检索依据。 */
    @Transactional(readOnly = true)
    public SearchResponse searchBound(String query, Long sessionClassId, Set<Long> allowedMaterialIds, int limit) {
        return search(new SearchRequest(query, SearchMode.HYBRID, limit, null),
                sessionClassId, Set.copyOf(allowedMaterialIds));
    }

    private SearchResponse search(SearchRequest request, Long sessionClassId, Set<Long> allowedMaterialIds) {
        String query = validateQuery(request.query());
        int limit = request.limit() == null ? properties.defaultLimit() : request.limit();
        if (limit < 1 || limit > properties.maxLimit()) {
            throw new BadRequestException("Limit must be between 1 and " + properties.maxLimit());
        }
        SearchMode mode = request.mode() == null ? SearchMode.HYBRID : request.mode();
        if (allowedMaterialIds != null && allowedMaterialIds.isEmpty()) {
            return new SearchResponse(mode, NO_EVIDENCE, List.of());
        }

        List<ScoredId> keyword = mode == SearchMode.VECTOR ? List.of()
                : keyword(query, sessionClassId, allowedMaterialIds, limit * 3);
        List<ScoredId> vector = mode == SearchMode.KEYWORD ? List.of()
                : vector(query, sessionClassId, allowedMaterialIds, limit * 3);
        List<RankedId> ranked = rank(mode, keyword, vector).stream().limit(limit).toList();
        Map<Long, KnowledgeChunk> ready = loadReady(
                ranked.stream().map(RankedId::id).toList(), sessionClassId, allowedMaterialIds);
        Map<Long, Material> materialMap = materials.findAllById(ready.values().stream()
                        .map(KnowledgeChunk::getMaterialId).collect(Collectors.toSet())).stream()
                .filter(material -> material.getClassId().equals(sessionClassId)
                        && material.getIndexStatus() == IndexStatus.READY
                        && (allowedMaterialIds == null || allowedMaterialIds.contains(material.getId())))
                .collect(Collectors.toMap(Material::getId, Function.identity()));

        List<RetrievalHit> hits = new ArrayList<>();
        int finalRank = 1;
        for (RankedId item : ranked) {
            KnowledgeChunk chunk = ready.get(item.id());
            Material material = chunk == null ? null : materialMap.get(chunk.getMaterialId());
            if (material == null) {
                continue;
            }
            hits.add(new RetrievalHit(material.getId(), material.getTitle(), chunk.getKnowledgeEntryId(),
                    chunk.getId(), chunk.getChunkIndex(), chunk.getStartOffset(), chunk.getEndOffset(),
                    excerpt(chunk.getChunkText()), finalRank++, item.finalScore(), item.keywordScore(),
                    item.keywordRank(), item.vectorScore(), item.vectorRank()));
        }
        return new SearchResponse(mode, hits.isEmpty() ? NO_EVIDENCE : null, List.copyOf(hits));
    }

    private List<ScoredId> keyword(String query, Long classId, Set<Long> allowedMaterialIds, int limit) {
        List<ScoredId> result = new ArrayList<>();
        int rank = 1;
        List<KnowledgeChunkRepository.KeywordHitProjection> candidates = allowedMaterialIds == null
                ? chunks.searchReady(classId, query, limit)
                : chunks.searchReadyWithinMaterials(classId, allowedMaterialIds, query, limit);
        for (KnowledgeChunkRepository.KeywordHitProjection hit : candidates) {
            result.add(new ScoredId(hit.getId(), hit.getKeywordScore(), rank++));
        }
        return result;
    }

    private List<ScoredId> vector(String query, Long classId, Set<Long> allowedMaterialIds, int limit) {
        List<Double> queryVector = embeddings.embed(List.of(query)).get(0);
        List<ScoredId> result = new ArrayList<>();
        int rank = 1;
        for (VectorStoreGateway.VectorMatch match : vectors.search(
                queryVector, classId, limit, properties.vectorThreshold())) {
            result.add(new ScoredId(match.chunkId(), match.score(), rank++));
        }
        // 此回表在组装结果前再次执行；伪造 payload、跨班和非 READY point 均无法形成命中。
        Map<Long, KnowledgeChunk> ready = loadReady(
                result.stream().map(ScoredId::id).toList(), classId, allowedMaterialIds);
        return result.stream().filter(item -> ready.containsKey(item.id())).toList();
    }

    private List<RankedId> rank(SearchMode mode, List<ScoredId> keyword, List<ScoredId> vector) {
        Map<Long, ScoredId> keywordMap = index(keyword);
        Map<Long, ScoredId> vectorMap = index(vector);
        if (mode == SearchMode.KEYWORD) {
            return keyword.stream().map(item -> new RankedId(item.id(), item.score(), item.score(), item.rank(),
                    null, null)).toList();
        }
        if (mode == SearchMode.VECTOR) {
            return vector.stream().map(item -> new RankedId(item.id(), item.score(), null, null,
                    item.score(), item.rank())).toList();
        }
        return ReciprocalRankFusion.fuse(keyword.stream().map(ScoredId::id).toList(),
                        vector.stream().map(ScoredId::id).toList(), properties.rrfK()).stream()
                .map(item -> new RankedId(item.chunkId(), item.score(), score(keywordMap, item.chunkId()),
                        item.keywordRank(), score(vectorMap, item.chunkId()), item.vectorRank()))
                .toList();
    }

    private Map<Long, ScoredId> index(List<ScoredId> values) {
        return values.stream().collect(Collectors.toMap(ScoredId::id, Function.identity(), (left, right) -> left,
                LinkedHashMap::new));
    }

    private Double score(Map<Long, ScoredId> values, Long id) {
        return values.containsKey(id) ? values.get(id).score() : null;
    }

    private Map<Long, KnowledgeChunk> loadReady(Collection<Long> ids, Long classId) {
        return loadReady(ids, classId, null);
    }

    private Map<Long, KnowledgeChunk> loadReady(
            Collection<Long> ids, Long classId, Set<Long> allowedMaterialIds) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeChunk> rows = allowedMaterialIds == null
                ? chunks.findAllByIdInAndClassIdAndIndexStatus(ids, classId, IndexStatus.READY)
                : chunks.findAllByIdInAndClassIdAndIndexStatusAndMaterialIdIn(
                        ids, classId, IndexStatus.READY, allowedMaterialIds);
        return rows.stream()
                .collect(Collectors.toMap(KnowledgeChunk::getId, Function.identity()));
    }

    private String validateQuery(String value) {
        String query = value == null ? "" : value.trim();
        int length = query.codePointCount(0, query.length());
        if (length < 1 || length > properties.queryMaxCodePoints()) {
            throw new BadRequestException("Query must contain 1 to " + properties.queryMaxCodePoints() + " characters");
        }
        return query;
    }

    private String excerpt(String text) {
        int length = text.codePointCount(0, text.length());
        if (length <= 320) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, 320)) + "…";
    }

    private record ScoredId(Long id, double score, int rank) { }
    private record RankedId(Long id, double finalScore, Double keywordScore, Integer keywordRank,
                            Double vectorScore, Integer vectorRank) { }
}
