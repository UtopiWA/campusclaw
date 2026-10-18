package com.campusclaw.mcp;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 对外部工具文本执行有界截断和课程场景的最小注入模式过滤。 */
@Component
public class McpToolResultSanitizer {
    private static final int MAX_RESULT = 1200;
    private static final String FILTERED = "工具结果已被安全过滤";
    private static final List<String> BLOCKED = List.of(
            "忽略前述", "忽略此前指令", "ignore previous instructions", "你现在是", "system:");

    public SanitizedResult sanitize(String value) {
        String truncated = firstCodePoints(value == null ? "" : value, MAX_RESULT).trim();
        String lower = truncated.toLowerCase(Locale.ROOT);
        if (BLOCKED.stream().anyMatch(lower::contains)) {
            return new SanitizedResult(FILTERED, false);
        }
        return new SanitizedResult(truncated, !truncated.isBlank());
    }

    static String firstCodePoints(String value, int limit) {
        int count = value.codePointCount(0, value.length());
        return count <= limit ? value : value.substring(0, value.offsetByCodePoints(0, limit));
    }

    public record SanitizedResult(String text, boolean usable) { }
}
