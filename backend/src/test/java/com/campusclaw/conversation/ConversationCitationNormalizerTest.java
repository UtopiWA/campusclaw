package com.campusclaw.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationCitationNormalizerTest {
    private final ConversationCitationNormalizer normalizer = new ConversationCitationNormalizer();

    @Test
    void renumbersOnlyUsedCandidatesByFirstAppearance() {
        List<ConversationCitation> candidates = List.of(
                citation(1, 101L), citation(2, 102L), citation(4, 104L));

        ConversationCitationNormalizer.NormalizedAnswer result = normalizer.normalize(
                "先用[4]，再用[2]，重复[4]。", candidates);

        assertThat(result.content()).isEqualTo("先用[1]，再用[2]，重复[1]。");
        assertThat(result.citations()).extracting(ConversationCitation::number).containsExactly(1, 2);
        assertThat(result.citations()).extracting(ConversationCitation::chunkId).containsExactly(104L, 102L);
    }

    @Test
    void removesUnknownMarkersAndUnusedCandidates() {
        ConversationCitationNormalizer.NormalizedAnswer result = normalizer.normalize(
                "无效[99]，有效[3]。", List.of(citation(3, 103L), citation(4, 104L)));

        assertThat(result.content()).isEqualTo("无效，有效[1]。");
        assertThat(result.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.chunkId()).isEqualTo(103L));
    }

    @Test
    void addsSingleBestMatchingCitationWhenModelOmitsEveryMarker() {
        List<ConversationCitation> candidates = List.of(
                new ConversationCitation(1, 7L, "讲义", 101L, 0, "岳阳楼景色壮阔。"),
                new ConversationCitation(2, 7L, "讲义", 102L, 1, "《岳阳楼记》的作者是范仲淹。"),
                new ConversationCitation(3, 7L, "讲义", 103L, 2, "文章表达忧乐思想。"));

        ConversationCitationNormalizer.NormalizedAnswer result = normalizer.normalize(
                "这篇文章的作者是北宋文学家范仲淹。", candidates);

        assertThat(result.content()).isEqualTo("这篇文章的作者是北宋文学家范仲淹。 [1]");
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.number()).isEqualTo(1);
            assertThat(citation.chunkId()).isEqualTo(102L);
        });
    }

    @Test
    void omittedMarkerTieUsesEarlierRetrievalCandidateAndNoCandidatesStayUncited() {
        ConversationCitationNormalizer.NormalizedAnswer tied = normalizer.normalize(
                "完全改写的回答", List.of(citation(3, 103L), citation(4, 104L)));
        ConversationCitationNormalizer.NormalizedAnswer noCandidates = normalizer.normalize(
                "资料中未找到相关内容", List.of());

        assertThat(tied.citations()).singleElement()
                .satisfies(citation -> assertThat(citation.chunkId()).isEqualTo(103L));
        assertThat(tied.content()).endsWith("[1]");
        assertThat(noCandidates.content()).isEqualTo("资料中未找到相关内容");
        assertThat(noCandidates.citations()).isEmpty();
    }

    private ConversationCitation citation(int number, Long chunkId) {
        return new ConversationCitation(number, 7L, "讲义", chunkId, number - 1, "片段");
    }
}
