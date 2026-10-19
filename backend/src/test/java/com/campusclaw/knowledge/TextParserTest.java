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

    @Test
    void keepsDisplayMathAndExplanationInOneHeadingEntry() {
        String source = """
                # 参考答案与解析

                ## 四、应用题

                ### 20.

                （1）由两点距离公式：

                $$
                AB=\\sqrt{(7-1)^2+(10-2)^2}
                $$

                $$
                AB=\\sqrt{6^2+8^2}=10
                $$

                所以 $AB=10$。

                （2）因为 $AC=6$，$BC=8$，且：

                \\[
                AC^2+BC^2=6^2+8^2=100=AB^2
                \\]

                根据勾股定理的逆定理，三角形 $ABC$ 是直角三角形。
                """;

        List<String> entries = parser.parse(source.getBytes(StandardCharsets.UTF_8));

        assertThat(entries).hasSize(3);
        assertThat(entries.get(2))
                .startsWith("### 20.\n\n（1）由两点距离公式：")
                .contains("$$\nAB=\\sqrt{(7-1)^2+(10-2)^2}\n$$")
                .contains("\\[\nAC^2+BC^2=6^2+8^2=100=AB^2\n\\]")
                .endsWith("根据勾股定理的逆定理，三角形 $ABC$ 是直角三角形。");
    }

    @Test
    void preservesBlankLinesAndPseudoHeadingsInsideFences() {
        String source = """
                # 真实标题

                ```markdown
                # 代码中的伪标题

                代码中的空行不结束围栏
                ```

                $$
                # 公式中的伪标题

                x=1
                $$

                结论。

                ## 下一节

                正文。
                """;

        List<String> entries = parser.parse(source.getBytes(StandardCharsets.UTF_8));

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0))
                .contains("# 代码中的伪标题\n\n代码中的空行不结束围栏")
                .contains("# 公式中的伪标题\n\nx=1")
                .endsWith("结论。");
        assertThat(entries.get(1)).isEqualTo("## 下一节\n\n正文。");
    }
}
