package com.campusclaw.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {
    @Test
    void fusesRanksDeterministicallyWithoutAddingRawScores() {
        var result = ReciprocalRankFusion.fuse(List.of(2L, 1L, 2L), List.of(1L, 3L), 60);

        assertThat(result).extracting(ReciprocalRankFusion.FusedRank::chunkId)
                .containsExactly(1L, 2L, 3L);
        assertThat(result.get(0).score()).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(result.get(1).vectorRank()).isNull();
    }

    @Test
    void stableTieUsesBestRankThenChunkId() {
        var result = ReciprocalRankFusion.fuse(List.of(8L), List.of(7L), 60);
        assertThat(result).extracting(ReciprocalRankFusion.FusedRank::chunkId).containsExactly(7L, 8L);
    }

    @Test
    void candidateThatDominatesBothInputRanksCannotFallBehind() {
        var result = ReciprocalRankFusion.fuse(
                List.of(1L, 2L, 3L, 4L),
                List.of(3L, 1L, 4L, 2L), 60);

        assertThat(result).extracting(ReciprocalRankFusion.FusedRank::chunkId)
                .containsSubsequence(1L, 2L);
        assertThat(result.stream().filter(item -> item.chunkId().equals(1L)).findFirst().orElseThrow().score())
                .isGreaterThan(result.stream().filter(item -> item.chunkId().equals(2L)).findFirst().orElseThrow().score());
    }
}
