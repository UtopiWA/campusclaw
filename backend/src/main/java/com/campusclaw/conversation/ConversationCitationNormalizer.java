package com.campusclaw.conversation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 在对话落库前过滤并连续编号引用；模型完全漏标时只补一条文本最匹配依据。 */
@Component
public class ConversationCitationNormalizer {
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)]");

    public NormalizedAnswer normalize(String content, List<ConversationCitation> candidates) {
        Map<Integer, ConversationCitation> candidatesByNumber = new LinkedHashMap<>();
        for (ConversationCitation candidate : candidates == null ? List.<ConversationCitation>of() : candidates) {
            candidatesByNumber.putIfAbsent(candidate.number(), candidate);
        }

        Map<Integer, Integer> assignedNumbers = new LinkedHashMap<>();
        Matcher matcher = CITATION_MARKER.matcher(content == null ? "" : content);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            int originalNumber;
            try {
                originalNumber = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                matcher.appendReplacement(rewritten, "");
                continue;
            }
            ConversationCitation candidate = candidatesByNumber.get(originalNumber);
            if (candidate == null) {
                matcher.appendReplacement(rewritten, "");
                continue;
            }
            int displayNumber = assignedNumbers.computeIfAbsent(
                    originalNumber, ignored -> assignedNumbers.size() + 1);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("[" + displayNumber + "]"));
        }
        matcher.appendTail(rewritten);

        if (assignedNumbers.isEmpty() && !candidatesByNumber.isEmpty()) {
            ConversationCitation fallback = bestTextualMatch(rewritten.toString(), candidatesByNumber.values());
            String fallbackContent = rewritten.toString().stripTrailing() + " [1]";
            return new NormalizedAnswer(fallbackContent, List.of(renumber(fallback, 1)));
        }

        List<ConversationCitation> usedCitations = new ArrayList<>();
        assignedNumbers.forEach((originalNumber, displayNumber) -> {
            ConversationCitation source = candidatesByNumber.get(originalNumber);
            usedCitations.add(renumber(source, displayNumber));
        });
        return new NormalizedAnswer(rewritten.toString(), List.copyOf(usedCitations));
    }

    /** 模型漏标时只选择正文重合度最高的一条，避免退化为展示全部检索候选。 */
    private ConversationCitation bestTextualMatch(String answer, Iterable<ConversationCitation> candidates) {
        Set<String> answerBigrams = bigrams(answer);
        ConversationCitation best = null;
        int bestScore = -1;
        for (ConversationCitation candidate : candidates) {
            Set<String> evidenceBigrams = bigrams(candidate.excerpt());
            int score = 0;
            for (String bigram : answerBigrams) {
                if (evidenceBigrams.contains(bigram)) {
                    score++;
                }
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private Set<String> bigrams(String value) {
        int[] normalized = (value == null ? "" : value).toLowerCase(java.util.Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .toArray();
        Set<String> result = new HashSet<>();
        if (normalized.length == 1) {
            result.add(new String(normalized, 0, 1));
        }
        for (int index = 0; index + 1 < normalized.length; index++) {
            result.add(new String(normalized, index, 2));
        }
        return result;
    }

    private ConversationCitation renumber(ConversationCitation source, int number) {
        return new ConversationCitation(number, source.materialId(), source.materialTitle(),
                source.chunkId(), source.chunkIndex(), source.excerpt());
    }

    public record NormalizedAnswer(String content, List<ConversationCitation> citations) {
    }
}
