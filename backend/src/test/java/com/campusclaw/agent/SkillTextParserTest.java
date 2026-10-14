package com.campusclaw.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusclaw.common.BadRequestException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SkillTextParserTest {
    private final SkillTextParser parser = new SkillTextParser();

    @Test
    void parsesCommentsSeparatorsCrLfAndPreservesOriginalSource() {
        String source = "# 教师可编辑的说明\r\n"
                + "名称：数学解题引导\r\n"
                + "适用条件：方程，去括号、分数;通分|面积；应用题\r\n"
                + "资料：数学\r\n"
                + "工具：knowledge_search\r\n"
                + "启用：是\r\n\r\n"
                + "只指出下一步。\r\n不要给最终答案。";

        SkillDefinition result = parser.parse(source);

        assertThat(result.name()).isEqualTo("数学解题引导");
        assertThat(result.triggers()).containsExactly("方程", "去括号", "分数", "通分", "面积", "应用题");
        assertThat(result.materialScope()).isEqualTo("数学");
        assertThat(result.toolName()).isEqualTo("knowledge_search");
        assertThat(result.enabled()).isTrue();
        assertThat(result.instructions()).isEqualTo("只指出下一步。\n不要给最终答案。");
        assertThat(result.sourceText()).isEqualTo(source);
    }

    @Test
    void defaultsOptionalScopeAndToolAndDeduplicatesTriggers() {
        SkillDefinition result = parser.parse("名称: 阅读提示\n适用条件: 阅读, 阅读,段落\n启用: false\n\n逐段提示。 ");

        assertThat(result.triggers()).containsExactly("阅读", "段落");
        assertThat(result.materialScope()).isEmpty();
        assertThat(result.toolName()).isEqualTo("knowledge_search");
        assertThat(result.enabled()).isFalse();
        assertThat(result.instructions()).isEqualTo("逐段提示。");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSources")
    void rejectsInvalidSource(String description, String source, String expectedMessage) {
        assertThatThrownBy(() -> parser.parse(source))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> invalidSources() {
        return Stream.of(
                Arguments.of("空文本", " ", "不能为空"),
                Arguments.of("没有正文分隔", "名称：测试\n适用条件：测试\n启用：是", "空行"),
                Arguments.of("缺少名称", "适用条件：测试\n启用：是\n\n说明", "名称"),
                Arguments.of("空触发词", "名称：测试\n适用条件：,，；|\n启用：是\n\n说明", "关键词"),
                Arguments.of("缺少正文", "名称：测试\n适用条件：测试\n启用：是\n\n ", "正文"),
                Arguments.of("重复字段", "名称：测试\n名称：重复\n适用条件：测试\n启用：是\n\n说明", "重复"),
                Arguments.of("未知字段", "名称：测试\n适用条件：测试\n模型：任意\n启用：是\n\n说明", "未知"),
                Arguments.of("非法布尔值", "名称：测试\n适用条件：测试\n启用：启用\n\n说明", "启用字段"),
                Arguments.of("非法工具", "名称：测试\n适用条件：测试\n工具：shell\n启用：是\n\n说明", "knowledge_search"),
                Arguments.of("名称超长", "名称：" + "技".repeat(33) + "\n适用条件：测试\n启用：是\n\n说明", "32"),
                Arguments.of("适用条件超长", "名称：测试\n适用条件：" + "词".repeat(121)
                        + "\n启用：是\n\n说明", "120"),
                Arguments.of("资料超长", "名称：测试\n适用条件：测试\n资料：" + "数".repeat(33)
                        + "\n启用：是\n\n说明", "32"),
                Arguments.of("说明超长", "名称：测试\n适用条件：测试\n启用：是\n\n" + "说".repeat(2001), "2000"),
                Arguments.of("总长度超限", "名称：测试\n适用条件：测试\n启用：是\n\n" + "说".repeat(8001), "8000")
        );
    }
}
