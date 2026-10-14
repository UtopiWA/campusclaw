package com.campusclaw.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantSkillRepository extends JpaRepository<AssistantSkill, AssistantSkill.Key> {
    List<AssistantSkill> findAllByAssistantId(Long assistantId);
    List<AssistantSkill> findAllByAssistantIdIn(Collection<Long> assistantIds);
    boolean existsByAssistantIdAndSkillId(Long assistantId, Long skillId);
    void deleteAllByAssistantId(Long assistantId);
}
