package com.campusclaw.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Skill, Long> {
    Optional<Skill> findFirstByAssistantIdAndName(Long assistantId, String name);
}
