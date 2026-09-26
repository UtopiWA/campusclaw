package com.campusclaw.knowledge;

import com.campusclaw.common.BadRequestException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Validates uploaded text and splits it into deterministic, Unicode-safe knowledge chunks. */
@Component
public class TextParser {
    static final int MAX_CHUNK_CODE_POINTS = 1000;

    public List<String> parse(byte[] bytes) {
        // Normalize line endings before using blank lines as paragraph boundaries.
        String text = decodeUtf8(bytes).replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isBlank()) {
            throw new BadRequestException("File must contain non-whitespace text");
        }

        List<String> chunks = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            appendChunks(paragraph.trim(), chunks);
        }
        if (chunks.isEmpty()) {
            throw new BadRequestException("File must contain non-whitespace text");
        }
        return List.copyOf(chunks);
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
