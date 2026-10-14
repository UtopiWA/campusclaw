package com.campusclaw.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Skill, Long> {
    Optional<Skill> findFirstByClassIdAndName(Long classId, String name);
    Optional<Skill> findByIdAndClassId(Long id, Long classId);
    List<Skill> findAllByClassIdOrderByCreatedAtAsc(Long classId);
    List<Skill> findAllByIdInAndClassId(Collection<Long> ids, Long classId);
    List<Skill> findAllByIdInAndClassIdAndEnabledTrue(Collection<Long> ids, Long classId);
    boolean existsByClassIdAndNameAndIdNot(Long classId, String name, Long id);
}
