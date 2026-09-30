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
        assertThat(chunks.get(1).text()).startsWith("## 第二节");
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
                .containsExactly("# 第一章", "## 空节", "### 内容\n正文");
    }
}
