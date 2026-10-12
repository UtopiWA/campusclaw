package com.campusclaw.retrieval;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.config.AppProperties;
import com.campusclaw.gateway.ChatGateway;
import com.campusclaw.gateway.ChatGateway.ChatMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 先检索本班依据再问答；无依据时绝不调用模型。 */
@Service
public class AskService {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final Pattern CITATION_VARIANT = Pattern.compile(
            "(?:\\[|［|【|\\(|（)\\s*(\\d+)\\s*(?:]|］|】|\\)|）)");
    private static final String SYSTEM_RULE = "你是 CampusClaw 教研助手。只能根据编号资料回答，不得补充资料外事实。"
            + "每个事实句末必须使用半角方括号标注一个或多个资料编号，例如 [1] 或 [1][2]；"
            + "回答必须至少包含一个有效编号，不得使用【1】、（1）或其他引用格式。资料不足时明确说明，不得编造。";
    static final String UNVERIFIABLE_ANSWER = "模型未能生成可验证引用，请直接核对下方依据片段。";

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
        String answer = normalizeCitations(chat.complete(List.copyOf(messages)), evidence.hits().size());
        CitationResult cited = renumberCitations(answer, evidence.hits());
        if (!cited.citations().isEmpty()) {
            return new AskResponse(cited.answer(), cited.citations());
        }

        // 部分兼容模型会忽略首次引用要求；只允许一次不增加事实的格式修订。
        List<ChatMessage> repairMessages = new ArrayList<>(messages);
        repairMessages.add(new ChatMessage("assistant", answer));
        repairMessages.add(new ChatMessage("user", citationRepairInstruction(evidence.hits().size())));
        String repaired = normalizeCitations(chat.complete(List.copyOf(repairMessages)), evidence.hits().size());
        CitationResult repairedResult = renumberCitations(repaired, evidence.hits());
        if (!repairedResult.citations().isEmpty()) {
            return new AskResponse(repairedResult.answer(), repairedResult.citations());
        }

        // 不向客户端返回无法与资料建立关系的自由回答，改为提供可直接核对的候选片段。
        return new AskResponse(UNVERIFIABLE_ANSWER, allCitations(evidence.hits()));
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
                .map(item -> "assistant".equalsIgnoreCase(item.role())
                        ? new AskRequest.HistoryMessage(item.role(), stripHistoricalCitations(item.content()))
                        : item)
                .toList();
        return filtered.subList(Math.max(0, filtered.size() - 6), filtered.size());
    }

    /** 以正文首次出现顺序同时重写编号和 citations，前端无需再次猜测编号。 */
    private CitationResult renumberCitations(String answer, List<RetrievalHit> hits) {
        Map<Integer, Integer> displayNumbers = new LinkedHashMap<>();
        Matcher matcher = CITATION.matcher(answer);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            int candidateNumber = Integer.parseInt(matcher.group(1));
            int displayNumber = displayNumbers.computeIfAbsent(candidateNumber,
                    ignored -> displayNumbers.size() + 1);
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("[" + displayNumber + "]"));
        }
        matcher.appendTail(rewritten);
        List<AskResponse.Citation> citations = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : displayNumbers.entrySet()) {
            citations.add(toCitation(entry.getValue(), hits.get(entry.getKey() - 1)));
        }
        return new CitationResult(rewritten.toString(), List.copyOf(citations));
    }

    private List<AskResponse.Citation> allCitations(List<RetrievalHit> hits) {
        List<AskResponse.Citation> citations = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            citations.add(toCitation(index + 1, hits.get(index)));
        }
        return List.copyOf(citations);
    }

    private AskResponse.Citation toCitation(int number, RetrievalHit hit) {
        return new AskResponse.Citation(number, hit.materialId(), hit.materialTitle(),
                hit.chunkId(), hit.chunkIndex(), hit.excerpt());
    }

    private String normalizeCitations(String answer, int evidenceCount) {
        Matcher matcher = CITATION_VARIANT.matcher(answer);
        StringBuffer sanitized = new StringBuffer();
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(sanitized,
                    number >= 1 && number <= evidenceCount ? "[" + number + "]" : "");
        }
        matcher.appendTail(sanitized);
        return sanitized.toString().trim();
    }

    private String citationRepairInstruction(int evidenceCount) {
        return "上一回答缺少可验证的半角引用编号。请保持原有事实不变、不得新增内容，"
                + "仅重写为每个事实句末带 [n] 的回答；n 只能是 1 到 " + evidenceCount
                + "，至少使用一个编号，只输出修订后的回答。";
    }

    private String stripHistoricalCitations(String content) {
        return CITATION.matcher(content == null ? "" : content).replaceAll("").replaceAll("[ \\t]{2,}", " ").trim();
    }

    private String validateQuestion(String value) {
        String question = value == null ? "" : value.trim();
        int length = question.codePointCount(0, question.length());
        if (length < 1 || length > maxQuestionCodePoints) {
            throw new BadRequestException("Question must contain 1 to " + maxQuestionCodePoints + " characters");
        }
        return question;
    }

    private record CitationResult(String answer, List<AskResponse.Citation> citations) {
    }
}
