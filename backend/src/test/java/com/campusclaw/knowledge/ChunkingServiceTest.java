package com.campusclaw.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.persistence.IndexStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {
    private final ChunkingService service = new ChunkingService();

    @Test
    void autoUsesCodePointOffsetsOverlapAndContinuousMaterialIndexes() {
        String first = "😀" + "甲".repeat(900);
        List<ChunkDraft> chunks = service.chunkEntries(List.of(
                new ChunkingService.SourceEntry(11L, first),
                new ChunkingService.SourceEntry(12L, "第二条目")), ChunkingOptions.auto());

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(ChunkDraft::chunkIndex).containsExactly(0, 1, 2);
        assertThat(chunks.get(0).text().codePointAt(0)).isEqualTo(0x1F600);
        assertThat(chunks.get(0).startOffset()).isZero();
        assertThat(chunks.get(0).endOffset()).isEqualTo(800);
        assertThat(chunks.get(1).startOffset()).isEqualTo(720);
        assertThat(chunks.get(2).sourceEntryId()).isEqualTo(12L);
        assertThat(first.substring(0, first.offsetByCodePoints(0, 800))).isEqualTo(chunks.get(0).text());
    }

    @Test
    void autoPrefersParagraphBreaksAndAlwaysAdvancesWithoutEmptyChunks() {
        String source = "甲".repeat(450) + "\n\n" + "乙".repeat(950);
        List<ChunkDraft> chunks = service.chunkEntries(
                List.of(new ChunkingService.SourceEntry(1L, source)), ChunkingOptions.auto());

        assertThat(chunks.get(0).endOffset()).isEqualTo(450);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text()).isNotBlank();
            assertThat(chunk.endOffset()).isGreaterThan(chunk.startOffset());
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(800);
        });
        assertThat(chunks.get(chunks.size() - 1).endOffset())
                .isEqualTo(source.codePointCount(0, source.length()));
    }

    @Test
    void customPreprocessingDoesNotMutateSourceAndRejectsInvalidOptions() {
        String source = "联系 test@example.com   查看 https://example.com  课程内容";
        ChunkingOptions options = new ChunkingOptions(
                IndexStrategy.CUSTOM, 100, 0, BreakPreference.SENTENCE, true);
        List<ChunkDraft> chunks = service.chunkEntries(
                List.of(new ChunkingService.SourceEntry(1L, source)), options);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.text()).isEqualTo("联系 查看 课程内容");
            assertThat(chunk.endOffset()).isEqualTo(chunk.text().codePointCount(0, chunk.text().length()));
        });
        assertThat(source).contains("test@example.com", "https://example.com");
        assertThatThrownBy(() -> service.chunkEntries(List.of(new ChunkingService.SourceEntry(1L, source)),
                new ChunkingOptions(IndexStrategy.CUSTOM, 99, 0, BreakPreference.LINE, false)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void customAcceptsBoundariesAndRejectsEveryOutOfRangeOrIncompleteOption() {
        String source = "甲".repeat(2100);
        assertThat(service.chunkEntries(List.of(new ChunkingService.SourceEntry(1L, source)),
                new ChunkingOptions(IndexStrategy.CUSTOM, 100, 0, BreakPreference.LINE, false)))
                .allSatisfy(chunk -> assertThat(chunk.text()).hasSize(100));
        assertThat(service.chunkEntries(List.of(new ChunkingService.SourceEntry(1L, source)),
                new ChunkingOptions(IndexStrategy.CUSTOM, 2000, 50, BreakPreference.SENTENCE, false)))
                .hasSize(2);
        assertThat(service.chunkEntries(List.of(new ChunkingService.SourceEntry(1L, " \n\t ")),
                new ChunkingOptions(IndexStrategy.CUSTOM, 100, 0, BreakPreference.PARAGRAPH, false)))
                .isEmpty();

        for (ChunkingOptions invalid : List.of(
                new ChunkingOptions(IndexStrategy.CUSTOM, 2001, 0, BreakPreference.LINE, false),
                new ChunkingOptions(IndexStrategy.CUSTOM, 100, 51, BreakPreference.LINE, false),
                new ChunkingOptions(IndexStrategy.CUSTOM, 100, 0, null, false))) {
            assertThatThrownBy(() -> service.chunkEntries(
                    List.of(new ChunkingService.SourceEntry(1L, source)), invalid))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void hierarchyPreservesHeadingsAndFallsBackForLongSections() {
        String text = "# 第一章\n内容\n## 第二节\n" + "长".repeat(900);
        List<ChunkDraft> chunks = service.chunkEntries(
                List.of(new ChunkingService.SourceEntry(1L, text)), ChunkingOptions.hierarchy());

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks.get(0).text()).startsWith("# 第一章");
        assertThat(chunks.get(1).text()).startsWith("# 第一章\n## 第二节");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().codePointCount(0, chunk.text().length()))
                .isLessThanOrEqualTo(800));
    }

    @Test
    void hierarchyFallsBackWithoutHeadingsAndKeepsConsecutiveHeadings() {
        String withoutHeading = "正文".repeat(500);
        List<ChunkDraft> hierarchy = service.chunkEntries(
                List.of(new ChunkingService.SourceEntry(1L, withoutHeading)), ChunkingOptions.hierarchy());
        List<ChunkDraft> automatic = service.chunkEntries(
                List.of(new ChunkingService.SourceEntry(1L, withoutHeading)), ChunkingOptions.auto());
        assertThat(hierarchy).extracting(ChunkDraft::text)
                .containsExactlyElementsOf(automatic.stream().map(ChunkDraft::text).toList());

        List<ChunkDraft> consecutive = service.chunkEntries(
                List.of(new ChunkingService.SourceEntry(1L, "# 第一章\n## 空节\n### 内容\n正文")),
                ChunkingOptions.hierarchy());
        assertThat(consecutive).extracting(ChunkDraft::text)
                .containsExactly("# 第一章\n## 空节\n### 内容\n正文");
    }

    @Test
    void hierarchyCarriesHeadingPathAcrossEntriesWithoutIndexingTitleOnlyEntries() {
        List<ChunkDraft> chunks = service.chunkEntries(List.of(
                new ChunkingService.SourceEntry(1L, "# 《岳阳楼记》课文解析讲义"),
                new ChunkingService.SourceEntry(2L, "---"),
                new ChunkingService.SourceEntry(3L, "## 一、课文基本信息"),
                new ChunkingService.SourceEntry(4L, "**作者：**范仲淹"),
                new ChunkingService.SourceEntry(5L, "## 二、写作背景"),
                new ChunkingService.SourceEntry(6L, "↓"),
                new ChunkingService.SourceEntry(7L, "滕子京被贬后请范仲淹作记。")),
                ChunkingOptions.hierarchy());

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(ChunkDraft::text).containsExactly(
                "# 《岳阳楼记》课文解析讲义\n## 一、课文基本信息\n**作者：**范仲淹",
                "# 《岳阳楼记》课文解析讲义\n## 二、写作背景\n滕子京被贬后请范仲淹作记。");
        assertThat(chunks).extracting(ChunkDraft::sourceEntryId).containsExactly(4L, 7L);
        assertThat(chunks).extracting(ChunkDraft::startOffset).containsExactly(0, 0);
        assertThat(chunks).extracting(ChunkDraft::chunkIndex).containsExactly(0, 1);
    }

    @Test
    void hierarchyCountsContextPrefixWithinLimitAndKeepsBodyOffsets() {
        String body = "😀" + "文".repeat(900);
        List<ChunkDraft> chunks = service.chunkEntries(List.of(
                new ChunkingService.SourceEntry(1L, "# 总标题"),
                new ChunkingService.SourceEntry(2L, "## 小节"),
                new ChunkingService.SourceEntry(3L, body)), ChunkingOptions.hierarchy());

        assertThat(chunks).hasSize(2).allSatisfy(chunk ->
                assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(800));
        assertThat(chunks.get(0).text()).startsWith("# 总标题\n## 小节\n😀");
        assertThat(chunks.get(0).startOffset()).isZero();
        assertThat(chunks.get(0).endOffset()).isLessThanOrEqualTo(body.codePointCount(0, body.length()));
    }

    @Test
    void hierarchyIgnoresPseudoHeadingsInsideCodeAndMathFences() {
        String text = """
                # 真实标题
                ```markdown
                # 代码中的伪标题
                示例
                ```
                $$
                # 公式中的伪标题
                x=1
                $$
                围栏后的正文
                ## 下一节
                下一节正文
                """;

        List<ChunkDraft> chunks = service.chunkEntries(
                List.of(new ChunkingService.SourceEntry(1L, text)), ChunkingOptions.hierarchy());

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text())
                .startsWith("# 真实标题\n```markdown")
                .contains("# 代码中的伪标题", "# 公式中的伪标题", "围栏后的正文");
        assertThat(chunks.get(1).text()).startsWith("# 真实标题\n## 下一节\n下一节正文");
    }
}
