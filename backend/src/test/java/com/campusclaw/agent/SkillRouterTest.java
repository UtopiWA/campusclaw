package com.campusclaw.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusclaw.persistence.AssistantSkill;
import com.campusclaw.persistence.AssistantSkillRepository;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.SkillRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillRouterTest {
    private final AssistantSkillRepository bindings = mock(AssistantSkillRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillRouter router = new SkillRouter(bindings, skills);

    @Test
    void choosesHighestScoreFromBoundEnabledSameClassSkills() {
        when(bindings.findAllByAssistantId(4L)).thenReturn(List.of(binding(4L, 8L), binding(4L, 9L)));
        Skill oneHit = skill(8L, "方程,阅读");
        Skill twoHits = skill(9L, "方程,去括号,方程");
        when(skills.findAllByIdInAndClassIdAndEnabledTrue(any(), eq(2L)))
                .thenReturn(List.of(oneHit, twoHits));

        assertThat(router.route(4L, 2L, "请解释这个方程怎样去括号"))
                .contains(twoHits);
    }

    @Test
    void breaksPositiveTieByLowestDatabaseIdAndIgnoresCase() {
        when(bindings.findAllByAssistantId(4L)).thenReturn(List.of(binding(4L, 9L), binding(4L, 5L)));
        Skill highId = skill(9L, "READING");
        Skill lowId = skill(5L, "reading");
        when(skills.findAllByIdInAndClassIdAndEnabledTrue(any(), eq(2L)))
                .thenReturn(List.of(highId, lowId));

        assertThat(router.route(4L, 2L, "Reading practice")).contains(lowId);
    }

    @Test
    void returnsEmptyForNoBindingOrZeroScore() {
        when(bindings.findAllByAssistantId(4L)).thenReturn(List.of());
        assertThat(router.route(4L, 2L, "任意问题")).isEmpty();
        verifyNoInteractions(skills);

        when(bindings.findAllByAssistantId(5L)).thenReturn(List.of(binding(5L, 8L)));
        Skill unrelated = skill(8L, "方程");
        when(skills.findAllByIdInAndClassIdAndEnabledTrue(any(), eq(2L)))
                .thenReturn(List.of(unrelated));
        assertThat(router.route(5L, 2L, "阅读理解")).isEmpty();
    }

    @Test
    void broadExerciseGuideMatchesConcreteProblemButNotOrdinaryStatement() {
        when(bindings.findAllByAssistantId(4L)).thenReturn(List.of(binding(4L, 8L)));
        Skill guide = skill(8L, "学生提问各类习题");
        when(guide.getName()).thenReturn("解题引导");
        when(skills.findAllByIdInAndClassIdAndEnabledTrue(any(), eq(2L))).thenReturn(List.of(guide));

        assertThat(router.route(4L, 2L, "已知 2x + 3 = 9，x 等于多少？")).contains(guide);
        assertThat(router.route(4L, 2L, "今天我们学习了新的章节")).isEmpty();
    }

    @Test
    void calculatorSkillMatchesAnAdaptableExpressionWhenNaturalLanguageTriggerDoesNot() {
        when(bindings.findAllByAssistantId(4L)).thenReturn(List.of(binding(4L, 9L)));
        Skill calculator = skill(9L, "需要进行四则运算,括号,乘方,取模及一些数学函数的计算时");
        when(calculator.getToolRefs()).thenReturn("calculator@campus-tools");
        when(skills.findAllByIdInAndClassIdAndEnabledTrue(any(), eq(2L))).thenReturn(List.of(calculator));

        assertThat(router.route(4L, 2L, "请计算 sqrt(81) + 2 ** 5")).contains(calculator);
        assertThat(router.route(4L, 2L, "计算2的20次幂")).contains(calculator);
        assertThat(router.route(4L, 2L, "请说明勾股定理")).isEmpty();
    }

    private AssistantSkill binding(Long assistantId, Long skillId) {
        return new AssistantSkill(assistantId, skillId);
    }

    private Skill skill(Long id, String triggers) {
        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(id);
        when(skill.getTriggerText()).thenReturn(triggers);
        return skill;
    }
}
