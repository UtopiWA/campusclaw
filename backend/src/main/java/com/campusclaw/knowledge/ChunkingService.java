package com.campusclaw.knowledge;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.persistence.IndexStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 将知识条目确定性切分为 Unicode 安全且可回溯的检索片段。 */
@Service
public class ChunkingService {
    private static final int HIERARCHY_MAX_CODE_POINTS = 800;
    private static final int HIERARCHY_MAX_PREFIX_CODE_POINTS = 240;
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+|www\\.\\S+");
    private static final Pattern EMAIL = Pattern.compile("(?i)[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})[ \\t]+.+$");

    public List<ChunkDraft> chunkEntries(List<SourceEntry> entries, ChunkingOptions requested) {
        ChunkingOptions options = requested.validated();
        validateEntries(entries);
        if (options.strategy() == IndexStrategy.HIERARCHY) {
            return hierarchyChunks(entries);
        }
        List<ChunkDraft> result = new ArrayList<>();
        int nextIndex = 0;
        for (SourceEntry entry : entries) {
            String input = options.preprocess() ? preprocess(entry.text()) : normalizeLineEndings(entry.text());
            List<Range> ranges = windowRanges(
                    input, options.maxCodePoints(), overlap(options), options.breakPreference());
            for (Range range : ranges) {
                String value = substringByCodePoints(input, range.start(), range.end());
                if (!value.isBlank()) {
                    result.add(new ChunkDraft(entry.id(), nextIndex++, value, range.start(), range.end()));
                }
            }
        }
        return List.copyOf(result);
    }

    private void validateEntries(List<SourceEntry> entries) {
        if (entries == null) {
            throw new BadRequestException("Knowledge entries are required");
        }
        for (SourceEntry entry : entries) {
            if (entry == null || entry.id() == null || entry.text() == null) {
                throw new BadRequestException("Knowledge entry is incomplete");
            }
        }
    }

    public String preprocess(String source) {
        String normalized = normalizeLineEndings(source);
        return EMAIL.matcher(URL.matcher(normalized).replaceAll(" ")).replaceAll(" ")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    /** 跨知识条目维护 Markdown 标题栈，纯标题只为后续正文提供检索上下文。 */
    private List<ChunkDraft> hierarchyChunks(List<SourceEntry> entries) {
        List<ChunkDraft> result = new ArrayList<>();
        String[] headingPath = new String[3];
        int nextIndex = 0;
        for (SourceEntry entry : entries) {
            String input = normalizeLineEndings(entry.text());
            for (HierarchySection section : hierarchySections(input)) {
                if (section.heading() != null) {
                    updateHeadingPath(headingPath, section.heading());
                }
                String body = substringByCodePoints(input, section.start(), section.end());
                if (!hasMeaningfulBody(body)) {
                    continue;
                }
                String prefix = hierarchyPrefix(headingPath);
                int prefixLength = prefix.codePointCount(0, prefix.length());
                int bodyLimit = Math.max(1, HIERARCHY_MAX_CODE_POINTS
                        - prefixLength - (prefix.isEmpty() ? 0 : 1));
                int bodyOverlap = Math.min(80, bodyLimit / 10);
                List<Range> ranges = windowRanges(input, section.start(), section.end(),
                        bodyLimit, bodyOverlap, BreakPreference.PARAGRAPH);
                for (Range range : ranges) {
                    String value = substringByCodePoints(input, range.start(), range.end());
                    if (value.isBlank()) {
                        continue;
                    }
                    String contextualText = prefix.isEmpty() ? value : prefix + "\n" + value;
                    result.add(new ChunkDraft(entry.id(), nextIndex++, contextualText,
                            range.start(), range.end()));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<HierarchySection> hierarchySections(String text) {
        List<Heading> headings = new ArrayList<>();
        MarkdownFenceTracker fences = new MarkdownFenceTracker();
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? text.length() : newline;
            String line = text.substring(lineStart, lineEnd);
            if (!fences.isOpen()) {
                Matcher matcher = HEADING.matcher(line);
                if (matcher.matches()) {
                    headings.add(new Heading(matcher.group(1).length(), matcher.group(),
                            text.codePointCount(0, lineStart), text.codePointCount(0, lineEnd)));
                }
            }
            fences.consume(line);
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        int total = text.codePointCount(0, text.length());
        if (headings.isEmpty()) {
            return List.of(new HierarchySection(null, 0, total));
        }

        List<HierarchySection> result = new ArrayList<>();
        if (headings.get(0).start() > 0) {
            result.add(new HierarchySection(null, 0, headings.get(0).start()));
        }
        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int end = index + 1 < headings.size() ? headings.get(index + 1).start() : total;
            result.add(new HierarchySection(heading, heading.end(), end));
        }
        return List.copyOf(result);
    }

    private void updateHeadingPath(String[] path, Heading heading) {
        int index = heading.level() - 1;
        path[index] = heading.line().trim();
        for (int deeper = index + 1; deeper < path.length; deeper++) {
            path[deeper] = null;
        }
    }

    private String hierarchyPrefix(String[] headingPath) {
        StringBuilder prefix = new StringBuilder();
        for (String heading : headingPath) {
            if (heading == null || heading.isBlank()) {
                continue;
            }
            if (!prefix.isEmpty()) {
                prefix.append('\n');
            }
            prefix.append(heading);
        }
        String value = prefix.toString();
        int length = value.codePointCount(0, value.length());
        if (length <= HIERARCHY_MAX_PREFIX_CODE_POINTS) {
            return value;
        }
        return substringByCodePoints(value, 0, HIERARCHY_MAX_PREFIX_CODE_POINTS);
    }

    private boolean hasMeaningfulBody(String value) {
        String withoutRules = value.replaceAll("(?m)^\\s*(?:-{3,}|_{3,}|\\*{3,})\\s*$", "").trim();
        return withoutRules.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private List<Range> windowRanges(String text, int max, int overlap, BreakPreference preference) {
        return windowRanges(text, 0, text.codePointCount(0, text.length()), max, overlap, preference);
    }

    private List<Range> windowRanges(String text, int sectionStart, int sectionEnd,
                                     int max, int overlap, BreakPreference preference) {
        List<Range> ranges = new ArrayList<>();
        int start = trimStart(text, sectionStart, sectionEnd);
        while (start < sectionEnd) {
            int hardEnd = Math.min(sectionEnd, start + max);
            int end = hardEnd == sectionEnd ? sectionEnd : preferredBreak(text, start, hardEnd, preference);
            if (end <= start) {
                end = hardEnd;
            }
            int trimmedEnd = trimEnd(text, start, end);
            if (trimmedEnd > start) {
                ranges.add(new Range(start, trimmedEnd));
            }
            if (end >= sectionEnd) {
                break;
            }
            int next = Math.max(start + 1, end - overlap);
            start = trimStart(text, next, sectionEnd);
        }
        return ranges;
    }

    private int preferredBreak(String text, int start, int hardEnd, BreakPreference preference) {
        int minimum = start + Math.max(1, (hardEnd - start) / 2);
        List<String> delimiters = switch (preference) {
            case PARAGRAPH -> List.of("\n\n", "\n", "。", "！", "？", ".", "!", "?");
            case LINE -> List.of("\n", "。", "！", "？", ".", "!", "?");
            case SENTENCE -> List.of("。", "！", "？", ".", "!", "?");
        };
        String window = substringByCodePoints(text, minimum, hardEnd);
        for (String delimiter : delimiters) {
            int charIndex = window.lastIndexOf(delimiter);
            if (charIndex >= 0) {
                int endChars = charIndex + delimiter.length();
                return minimum + window.codePointCount(0, endChars);
            }
        }
        return hardEnd;
    }

    private int trimStart(String text, int start, int end) {
        int cursor = start;
        while (cursor < end && Character.isWhitespace(codePointAt(text, cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int trimEnd(String text, int start, int end) {
        int cursor = end;
        while (cursor > start && Character.isWhitespace(codePointAt(text, cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private int codePointAt(String text, int codePointIndex) {
        return text.codePointAt(text.offsetByCodePoints(0, codePointIndex));
    }

    private String substringByCodePoints(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    private int overlap(ChunkingOptions options) {
        return options.maxCodePoints() * options.overlapPercent() / 100;
    }

    private String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    public record SourceEntry(Long id, String text) {
    }

    private record Heading(int level, String line, int start, int end) {
    }

    private record HierarchySection(Heading heading, int start, int end) {
    }

    private record Range(int start, int end) {
    }
}
