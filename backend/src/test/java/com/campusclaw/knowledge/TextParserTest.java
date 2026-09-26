package com.campusclaw.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.common.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextParserTest {
    private final TextParser parser = new TextParser();

    @Test
    void parsesParagraphsAndSplitsLongContentDeterministically() {
        String longParagraph = "教".repeat(1001);
        List<String> chunks = parser.parse(("第一段\n\n" + longParagraph).getBytes(StandardCharsets.UTF_8));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).isEqualTo("第一段");
        assertThat(chunks.get(1).codePointCount(0, chunks.get(1).length())).isEqualTo(1000);
        assertThat(chunks.get(2)).isEqualTo("教");
    }

    @Test
    void rejectsMalformedUtf8AndWhitespaceOnlyFiles() {
        assertThatThrownBy(() -> parser.parse(new byte[] {(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> parser.parse(" \n\n ".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BadRequestException.class);
    }
}

