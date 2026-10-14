package com.campusclaw.agent;

import com.campusclaw.persistence.AssistantSkillRepository;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.SkillRepository;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只在当前绑定技能内执行可重复的词法计分，每轮最多选择一项。 */
@Service
public class SkillRouter {
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
        return score;
    }

    private record ScoredSkill(Skill skill, int score) {
    }
}
