package com.campusclaw.mcp;

import com.campusclaw.persistence.McpServer;
import com.campusclaw.persistence.McpServerRepository;
import com.campusclaw.persistence.McpToolCall;
import com.campusclaw.persistence.McpToolCallRepository;
import com.campusclaw.persistence.McpToolCallStatus;
import com.campusclaw.persistence.McpToolRepository;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.UserAccount;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 按技能白名单串行执行 MCP 工具，并将任一失败限制在当前工具。 */
@Service
public class McpToolOrchestrator {
    private static final Pattern UNIT_EXPRESSION = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s*(mm|cm|km|m|kg|g)\\s*(?:to|转|转换为)\\s*(mm|cm|km|m|kg|g)",
            Pattern.CASE_INSENSITIVE);
    private final McpServerRepository servers;
    private final McpToolRepository tools;
    private final McpToolCallRepository calls;
    private final McpTokenCipher tokens;
    private final McpHttpClient client;
    private final McpToolResultSanitizer sanitizer;

    public McpToolOrchestrator(McpServerRepository servers, McpToolRepository tools,
                               McpToolCallRepository calls, McpTokenCipher tokens,
                               McpHttpClient client, McpToolResultSanitizer sanitizer) {
        this.servers = servers;
        this.tools = tools;
        this.calls = calls;
        this.tokens = tokens;
        this.client = client;
        this.sanitizer = sanitizer;
    }

    public McpTurnResult invoke(Optional<Skill> skill, String question, Long conversationId, UserAccount user) {
        if (skill.isEmpty()) return McpTurnResult.empty();
        List<ToolCallSummary> summaries = new ArrayList<>();
        List<McpTurnResult.ToolContext> contexts = new ArrayList<>();
        for (String ref : splitRefs(skill.get().getToolRefs())) {
            if ("knowledge_search".equals(ref)) continue;
            invokeOne(ref, question, conversationId, user, summaries, contexts);
        }
        return new McpTurnResult(List.copyOf(summaries), List.copyOf(contexts));
    }

    private void invokeOne(String ref, String question, Long conversationId, UserAccount user,
                           List<ToolCallSummary> summaries, List<McpTurnResult.ToolContext> contexts) {
        int separator = ref.indexOf('@');
        if (separator < 1) return;
        String toolName = ref.substring(0, separator);
        String serverName = ref.substring(separator + 1);
        Optional<McpServer> found = servers.findFirstByClassIdAndName(user.getClassId(), serverName);
        if (found.isEmpty()) {
            summaries.add(new ToolCallSummary(ref, "denied", "工具服务不可用"));
            return;
        }
        McpServer server = found.get();
        if (!server.isConnected()) {
            addAudited(server, user, conversationId, ref, McpToolCallStatus.DENIED, "工具服务未连接", summaries);
            return;
        }
        if (tools.findFirstByServerIdAndName(server.getId(), toolName).isEmpty()) {
            addAudited(server, user, conversationId, ref, McpToolCallStatus.DENIED, "工具未被发现", summaries);
            return;
        }
        Optional<Map<String, Object>> arguments = arguments(toolName, question);
        if (arguments.isEmpty()) {
            addAudited(server, user, conversationId, ref, McpToolCallStatus.DENIED, "工具参数不符合规则", summaries);
            return;
        }
        try {
            String raw = client.call(server.getBaseUrl(), tokens.decrypt(server.getAuthTokenCiphertext()),
                    toolName, arguments.get());
            McpToolResultSanitizer.SanitizedResult safe = sanitizer.sanitize(raw);
            String detail = safe.text().isBlank() ? "工具未返回可用结果" : firstCodePoints(safe.text(), 200);
            McpToolCallStatus status = safe.usable() ? McpToolCallStatus.OK : McpToolCallStatus.DENIED;
            boolean audited = audit(server, user, conversationId, ref, status, detail);
            if (!audited) {
                summaries.add(new ToolCallSummary(ref, "error", "调用审计写入失败"));
                return;
            }
            summaries.add(new ToolCallSummary(ref, status.name().toLowerCase(Locale.ROOT), detail));
            if (safe.usable()) contexts.add(new McpTurnResult.ToolContext(ref, safe.text()));
        } catch (RuntimeException exception) {
            addAudited(server, user, conversationId, ref, McpToolCallStatus.ERROR, "工具调用失败", summaries);
        }
    }

    private void addAudited(McpServer server, UserAccount user, Long conversationId, String ref,
                            McpToolCallStatus status, String detail, List<ToolCallSummary> summaries) {
        boolean stored = audit(server, user, conversationId, ref, status, detail);
        summaries.add(new ToolCallSummary(ref, stored ? status.name().toLowerCase(Locale.ROOT) : "error",
                stored ? detail : "调用审计写入失败"));
    }

    private boolean audit(McpServer server, UserAccount user, Long conversationId, String ref,
                          McpToolCallStatus status, String detail) {
        try {
            calls.saveAndFlush(new McpToolCall(user.getClassId(), server.getId(), user.getId(), conversationId,
                    ref, status, firstCodePoints(detail, 512)));
            return true;
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private Optional<Map<String, Object>> arguments(String toolName, String question) {
        if ("formula_hint".equals(toolName)) {
            return Optional.of(Map.of("query", firstCodePoints(question.trim(), 500)));
        }
        if ("unit_convert".equals(toolName)) {
            Matcher matcher = UNIT_EXPRESSION.matcher(question);
            if (!matcher.find()) return Optional.empty();
            String from = matcher.group(2).toLowerCase(Locale.ROOT);
            String to = matcher.group(3).toLowerCase(Locale.ROOT);
            if (isMass(from) != isMass(to)) return Optional.empty();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("value", new BigDecimal(matcher.group(1)));
            values.put("fromUnit", from);
            values.put("toUnit", to);
            return Optional.of(values);
        }
        return Optional.empty();
    }

    private boolean isMass(String unit) { return "g".equals(unit) || "kg".equals(unit); }
    private List<String> splitRefs(String refs) {
        return refs == null || refs.isBlank() ? List.of() : List.of(refs.split(","));
    }
    private String firstCodePoints(String value, int limit) {
        return McpToolResultSanitizer.firstCodePoints(value, limit);
    }
}
