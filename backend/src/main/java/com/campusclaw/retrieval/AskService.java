package com.campusclaw.retrieval;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.config.AppProperties;
import com.campusclaw.gateway.ChatGateway;
import com.campusclaw.gateway.ChatGateway.ChatMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 先检索本班依据再问答；无依据时绝不调用模型。 */
@Service
public class AskService {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final String SYSTEM_RULE = "你是 CampusClaw 教研助手。只能根据编号资料回答；每个事实后标注资料编号，如 [1]。资料不足时明确说明，不得编造。";

    private final RetrievalService retrieval;
    private final ChatGateway chat;
    private final int maxQuestionCodePoints;

    public AskService(RetrievalService retrieval, ChatGateway chat, AppProperties properties) {
        this.retrieval = retrieval;
        this.chat = chat;
        this.maxQuestionCodePoints = properties.retrieval().queryMaxCodePoints();
    }

    public AskResponse ask(AskRequest request, Long classId) {
        String question = validateQuestion(request.question());
        SearchResponse evidence = retrieval.search(new SearchRequest(question, SearchMode.HYBRID, 4, null), classId);
        if (evidence.hits().isEmpty()) {
            return new AskResponse(RetrievalService.NO_EVIDENCE, List.of());
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", SYSTEM_RULE));
        for (AskRequest.HistoryMessage item : allowedHistory(request.history())) {
            messages.add(new ChatMessage(item.role().toLowerCase(), item.content()));
        }
        messages.add(new ChatMessage("user", buildGroundedPrompt(question, evidence.hits())));
        String answer = removeInvalidCitations(chat.complete(List.copyOf(messages)), evidence.hits().size());
        return new AskResponse(answer, citationsUsedBy(answer, evidence.hits()));
    }

    String buildGroundedPrompt(String question, List<RetrievalHit> hits) {
        StringBuilder prompt = new StringBuilder("以下资料均来自当前班级：\n");
        for (int index = 0; index < hits.size(); index++) {
            RetrievalHit hit = hits.get(index);
            prompt.append('[').append(index + 1).append("] ")
                    .append(hit.materialTitle()).append("：").append(hit.excerpt()).append('\n');
        }
        return prompt.append("\n问题：").append(question).toString();
    }

    private List<AskRequest.HistoryMessage> allowedHistory(List<AskRequest.HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<AskRequest.HistoryMessage> filtered = history.stream()
                .filter(item -> "user".equalsIgnoreCase(item.role()) || "assistant".equalsIgnoreCase(item.role()))
                .toList();
        return filtered.subList(Math.max(0, filtered.size() - 6), filtered.size());
    }

    private List<AskResponse.Citation> citationsUsedBy(String answer, List<RetrievalHit> hits) {
        Set<Integer> used = new HashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (number >= 1 && number <= hits.size()) {
                used.add(number);
            }
        }
        List<AskResponse.Citation> citations = new ArrayList<>();
        for (int number = 1; number <= hits.size(); number++) {
            if (used.contains(number)) {
                RetrievalHit hit = hits.get(number - 1);
                citations.add(new AskResponse.Citation(number, hit.materialId(), hit.materialTitle(),
                        hit.chunkId(), hit.chunkIndex(), hit.excerpt()));
            }
        }
        return List.copyOf(citations);
    }

    private String removeInvalidCitations(String answer, int evidenceCount) {
        Matcher matcher = CITATION.matcher(answer);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(sanitized,
                    number >= 1 && number <= evidenceCount ? Matcher.quoteReplacement(matcher.group()) : "");
        }
        matcher.appendTail(sanitized);
        return sanitized.toString().trim();
    }

    private String validateQuestion(String value) {
        String question = value == null ? "" : value.trim();
        int length = question.codePointCount(0, question.length());
        if (length < 1 || length > maxQuestionCodePoints) {
            throw new BadRequestException("Question must contain 1 to " + maxQuestionCodePoints + " characters");
        }
        return question;
    }
}
