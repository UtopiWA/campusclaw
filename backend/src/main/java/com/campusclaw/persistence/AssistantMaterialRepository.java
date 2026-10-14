package com.campusclaw.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantMaterialRepository extends JpaRepository<AssistantMaterial, AssistantMaterial.Key> {
    List<AssistantMaterial> findAllByAssistantId(Long assistantId);
    List<AssistantMaterial> findAllByAssistantIdIn(Collection<Long> assistantIds);
    void deleteAllByAssistantId(Long assistantId);
}
