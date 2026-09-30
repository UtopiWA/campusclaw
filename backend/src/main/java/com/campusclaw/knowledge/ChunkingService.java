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
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+|www\\.\\S+");
    private static final Pattern EMAIL = Pattern.compile("(?i)[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,3}[ \\t]+.+$");

    public List<ChunkDraft> chunkEntries(List<SourceEntry> entries, ChunkingOptions requested) {
        ChunkingOptions options = requested.validated();
        List<ChunkDraft> result = new ArrayList<>();
        int nextIndex = 0;
        for (SourceEntry entry : entries) {
            if (entry == null || entry.id() == null || entry.text() == null) {
                throw new BadRequestException("Knowledge entry is incomplete");
            }
            String input = options.preprocess() ? preprocess(entry.text()) : normalizeLineEndings(entry.text());
            List<Range> ranges = options.strategy() == IndexStrategy.HIERARCHY
                    ? hierarchyRanges(input)
                    : windowRanges(input, options.maxCodePoints(), overlap(options), options.breakPreference());
            for (Range range : ranges) {
                String value = substringByCodePoints(input, range.start(), range.end());
                if (!value.isBlank()) {
                    result.add(new ChunkDraft(entry.id(), nextIndex++, value, range.start(), range.end()));
                }
            }
        }
        return List.copyOf(result);
    }

    public String preprocess(String source) {
        String normalized = normalizeLineEndings(source);
        return EMAIL.matcher(URL.matcher(normalized).replaceAll(" ")).replaceAll(" ")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .trim();
    }

    private List<Range> hierarchyRanges(String text) {
        Matcher matcher = HEADING.matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(text.codePointCount(0, matcher.start()));
        }
        if (starts.isEmpty()) {
            return windowRanges(text, 800, 80, BreakPreference.PARAGRAPH);
        }

        List<Range> result = new ArrayList<>();
        int total = text.codePointCount(0, text.length());
        if (starts.get(0) > 0) {
            result.addAll(windowRanges(text, 0, starts.get(0), 800, 80, BreakPreference.PARAGRAPH));
        }
        for (int index = 0; index < starts.size(); index++) {
            int start = starts.get(index);
            int end = index + 1 < starts.size() ? starts.get(index + 1) : total;
            result.addAll(windowRanges(text, start, end, 800, 80, BreakPreference.PARAGRAPH));
        }
        return result;
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

    private record Range(int start, int end) {
    }
}
