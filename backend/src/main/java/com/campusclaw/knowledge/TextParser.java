package com.campusclaw.knowledge;

import com.campusclaw.common.BadRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 校验上传文本，并按 Markdown 章节形成确定且 Unicode 安全的知识条目。 */
@Component
public class TextParser {
    static final int MAX_CHUNK_CODE_POINTS = 1000;
    private static final Pattern SECTION_HEADING = Pattern.compile("^#{1,3}[ \\t]+.+$");

    public List<String> parse(byte[] bytes) {
        // 统一换行后再识别 Markdown 块，避免平台换行差异改变条目边界。
        String text = decodeUtf8(bytes).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isBlank()) {
            throw new BadRequestException("File must contain non-whitespace text");
        }

        List<String> chunks = packBlocks(markdownBlocks(text));
        if (chunks.isEmpty()) {
            throw new BadRequestException("File must contain non-whitespace text");
        }
        return List.copyOf(chunks);
    }

    private List<String> markdownBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        MarkdownFenceTracker fences = new MarkdownFenceTracker();
        for (String line : text.split("\\n", -1)) {
            boolean insideFence = fences.isOpen();
            if (!insideFence && isSectionHeading(line) && !current.isEmpty()) {
                appendBlock(current, blocks);
            }
            if (line.isBlank() && !insideFence) {
                appendBlock(current, blocks);
                continue;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
            fences.consume(line);
        }
        appendBlock(current, blocks);
        return blocks;
    }

    private List<String> packBlocks(List<String> blocks) {
        List<String> entries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (isSectionHeading(firstLine(block)) && !current.isEmpty()) {
                appendChunks(current.toString(), entries);
                current.setLength(0);
            }
            int separatorLength = current.isEmpty() ? 0 : 2;
            int combinedLength = current.codePointCount(0, current.length())
                    + separatorLength + block.codePointCount(0, block.length());
            if (!current.isEmpty() && combinedLength > MAX_CHUNK_CODE_POINTS) {
                appendChunks(current.toString(), entries);
                current.setLength(0);
            }
            if (block.codePointCount(0, block.length()) > MAX_CHUNK_CODE_POINTS) {
                appendChunks(block, entries);
                continue;
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        appendChunks(current.toString(), entries);
        return entries;
    }

    private void appendBlock(StringBuilder current, List<String> blocks) {
        String block = current.toString().trim();
        if (!block.isBlank()) {
            blocks.add(block);
        }
        current.setLength(0);
    }

    private boolean isSectionHeading(String line) {
        return SECTION_HEADING.matcher(line).matches();
    }

    private String firstLine(String block) {
        int newline = block.indexOf('\n');
        return newline < 0 ? block : block.substring(0, newline);
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new BadRequestException("File must be valid UTF-8", exception);
        }
    }

    private void appendChunks(String paragraph, List<String> chunks) {
        if (paragraph.isBlank()) {
            return;
        }
        int start = 0;
        while (start < paragraph.length()) {
            // Count code points rather than UTF-16 chars so surrogate pairs are never split in half.
            int remainingCodePoints = paragraph.codePointCount(start, paragraph.length());
            int count = Math.min(MAX_CHUNK_CODE_POINTS, remainingCodePoints);
            int end = paragraph.offsetByCodePoints(start, count);
            String chunk = paragraph.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            start = end;
        }
    }
}
