package com.campusclaw.agent;

import com.campusclaw.mcp.CalculatorExpressionAdapter;
import com.campusclaw.persistence.AssistantSkillRepository;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.SkillRepository;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只在当前绑定技能内执行可重复的词法计分，每轮最多选择一项。 */
@Service
public class SkillRouter {
    private static final Set<String> EXERCISE_CATEGORIES = Set.of("习题", "题目", "题型", "练习", "解题");
    private static final Pattern EXERCISE_WORDS = Pattern.compile(
            "求出|求解|求证|计算|证明|化简|解方程|怎么做|如何解|下列|选择题|填空题|"
                    + "已知|若.+则|多少|答案|结果|方程|函数|几何");
    private static final Pattern MATH_EXPRESSION = Pattern.compile(
            ".*(?:\\d\\s*[+\\-×÷*/=<>]|[+\\-×÷*/=<>]\\s*\\d|[a-z]\\s*[=+\\-*/^]).*",
            Pattern.CASE_INSENSITIVE);
    private final AssistantSkillRepository assistantSkills;
    private final SkillRepository skills;

    public SkillRouter(AssistantSkillRepository assistantSkills, SkillRepository skills) {
        this.assistantSkills = assistantSkills;
        this.skills = skills;
    }

    @Transactional(readOnly = true)
    public Optional<Skill> route(Long assistantId, Long classId, String question) {
        Set<Long> boundIds = assistantSkills.findAllByAssistantId(assistantId).stream()
                .map(link -> link.getSkillId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (boundIds.isEmpty()) {
            return Optional.empty();
        }
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<ScoredSkill> scored = skills.findAllByIdInAndClassIdAndEnabledTrue(boundIds, classId).stream()
                .map(skill -> new ScoredSkill(skill, score(skill, normalizedQuestion)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingInt(ScoredSkill::score).reversed()
                        .thenComparing(candidate -> candidate.skill().getId()))
                .toList();
        return scored.isEmpty() ? Optional.empty() : Optional.of(scored.get(0).skill());
    }

    private int score(Skill skill, String normalizedQuestion) {
        int score = 0;
        Set<String> distinct = new LinkedHashSet<>();
        for (String raw : skill.getTriggerText().split(",")) {
            String keyword = raw.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty()) {
                distinct.add(keyword);
            }
        }
        for (String keyword : distinct) {
            if (normalizedQuestion.contains(keyword)) {
                score++;
            }
        }
        if (score == 0 && isBroadExerciseGuide(skill, distinct) && looksLikeExercise(normalizedQuestion)) {
            score = 1;
        }
        if (score == 0 && hasCalculator(skill)
                && CalculatorExpressionAdapter.adapt(normalizedQuestion).isPresent()) {
            score = 1;
        }
        return score;
    }

    /** 计算器技能对可安全适配的纯计算请求提供兜底，不要求用户复述配置中的长触发短语。 */
    private boolean hasCalculator(Skill skill) {
        return skill.getToolRefs() != null && List.of(skill.getToolRefs().split(",")).stream()
                .map(String::trim)
                .anyMatch(ref -> ref.startsWith("calculator@"));
    }

    /** 将“各类习题”这类类别条件映射到固定题目特征，仍保持完全可重复的服务端路由。 */
    private boolean isBroadExerciseGuide(Skill skill, Set<String> triggers) {
        String name = skill.getName() == null ? "" : skill.getName();
        return name.contains("解题引导") && triggers.stream()
                .anyMatch(trigger -> EXERCISE_CATEGORIES.stream().anyMatch(trigger::contains));
    }

    private boolean looksLikeExercise(String question) {
        return EXERCISE_WORDS.matcher(question).find() || MATH_EXPRESSION.matcher(question).matches();
    }

    private record ScoredSkill(Skill skill, int score) {
    }
}
