package com.campusclaw.agent;

import com.campusclaw.common.BadRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 将课程约定的单文件技能格式解析为可验证、可重复的结构化配置。 */
@Component
public class SkillTextParser {
    private static final int MAX_SOURCE_LENGTH = 8000;
    private static final Pattern SECTION_BREAK = Pattern.compile("\\n[\\t ]*\\n");
    private static final Pattern TRIGGER_SEPARATOR = Pattern.compile("[,，、;；|]");
    private static final Set<String> ALLOWED_FIELDS = Set.of("名称", "适用条件", "资料", "工具", "启用");

    public SkillDefinition parse(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            throw new BadRequestException("技能文本不能为空");
        }
        String normalized = sourceText.replace("\r\n", "\n").replace('\r', '\n');
        requireMaxLength(sourceText, MAX_SOURCE_LENGTH, "技能文本不能超过 8000 字");

        Matcher separator = SECTION_BREAK.matcher(normalized);
        if (!separator.find()) {
            throw new BadRequestException("技能元数据与说明正文之间必须包含一个空行");
        }
        String metadata = normalized.substring(0, separator.start());
        String instructions = normalized.substring(separator.end()).strip();
        requireText(instructions, "技能说明正文不能为空");
        requireMaxLength(instructions, 2000, "技能说明不能超过 2000 字");

        Map<String, String> values = parseMetadata(metadata);
        String name = required(values, "名称");
        String triggerText = required(values, "适用条件");
        String materialScope = values.getOrDefault("资料", "").trim();
        String toolName = values.getOrDefault("工具", "knowledge_search").trim();
        boolean enabled = parseEnabled(required(values, "启用"));

        requireMaxLength(name, 32, "技能名称不能超过 32 字");
        requireMaxLength(triggerText, 120, "适用条件不能超过 120 字");
        requireMaxLength(materialScope, 32, "资料范围不能超过 32 字");
        if (!"knowledge_search".equals(toolName)) {
            throw new BadRequestException("工具只允许 knowledge_search");
        }
        List<String> triggers = splitTriggers(triggerText);
        if (triggers.isEmpty()) {
            throw new BadRequestException("适用条件至少包含一个关键词");
        }
        return new SkillDefinition(name, triggers, materialScope, toolName, enabled, instructions, sourceText);
    }

    private Map<String, String> parseMetadata(String metadata) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String rawLine : metadata.split("\\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = fieldSeparator(line);
            if (separator < 1) {
                throw new BadRequestException("技能元数据必须使用“字段：值”格式");
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!ALLOWED_FIELDS.contains(key)) {
                throw new BadRequestException("未知技能字段：" + key);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new BadRequestException("技能字段不能重复：" + key);
            }
        }
        return values;
    }

    private int fieldSeparator(String line) {
        int chinese = line.indexOf('：');
        int ascii = line.indexOf(':');
        if (chinese < 0) return ascii;
        if (ascii < 0) return chinese;
        return Math.min(chinese, ascii);
    }

    private List<String> splitTriggers(String triggerText) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : TRIGGER_SEPARATOR.split(triggerText)) {
            String clean = value.trim();
            if (!clean.isEmpty()) {
                values.add(clean);
            }
        }
        return new ArrayList<>(values);
    }

    private boolean parseEnabled(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "是", "true" -> true;
            case "否", "false" -> false;
            default -> throw new BadRequestException("启用字段只能是“是/否”或 true/false");
        };
    }

    private String required(Map<String, String> values, String field) {
        String value = values.get(field);
        requireText(value, "缺少必填技能字段：" + field);
        return value.trim();
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
    }

    private void requireMaxLength(String value, int max, String message) {
        if (value.codePointCount(0, value.length()) > max) {
            throw new BadRequestException(message);
        }
    }
}
