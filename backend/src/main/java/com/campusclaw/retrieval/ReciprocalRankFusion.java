package com.campusclaw.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 纯函数 RRF：只使用名次，不混加两个不可比较的原始分数。 */
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {
    }

    public static List<FusedRank> fuse(List<Long> keywordIds, List<Long> vectorIds, int k) {
        Map<Long, Integer> keywordRanks = ranks(keywordIds);
        Map<Long, Integer> vectorRanks = ranks(vectorIds);
        Map<Long, Double> scores = new HashMap<>();
        keywordRanks.forEach((id, rank) -> scores.merge(id, 1.0 / (k + rank), Double::sum));
        vectorRanks.forEach((id, rank) -> scores.merge(id, 1.0 / (k + rank), Double::sum));
        List<FusedRank> result = new ArrayList<>();
        scores.forEach((id, score) -> result.add(new FusedRank(id, score,
                keywordRanks.get(id), vectorRanks.get(id))));
        result.sort(Comparator.comparingDouble(FusedRank::score).reversed()
                .thenComparingInt(rank -> Math.min(valueOrMax(rank.keywordRank()), valueOrMax(rank.vectorRank())))
                .thenComparing(FusedRank::chunkId));
        return List.copyOf(result);
    }

    private static Map<Long, Integer> ranks(List<Long> ids) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            result.putIfAbsent(ids.get(index), index + 1);
        }
        return result;
    }

    private static int valueOrMax(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }

    public record FusedRank(Long chunkId, double score, Integer keywordRank, Integer vectorRank) {
    }
}
