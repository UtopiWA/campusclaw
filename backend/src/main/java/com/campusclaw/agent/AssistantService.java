package com.campusclaw.agent;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.persistence.Assistant;
import com.campusclaw.persistence.AssistantMaterial;
import com.campusclaw.persistence.AssistantMaterialRepository;
import com.campusclaw.persistence.AssistantRepository;
import com.campusclaw.persistence.AssistantSkill;
import com.campusclaw.persistence.AssistantSkillRepository;
import com.campusclaw.persistence.MaterialRepository;
import com.campusclaw.persistence.Role;
import com.campusclaw.persistence.SkillRepository;
import com.campusclaw.persistence.UserAccount;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 对智能体主体和两类绑定执行同班校验及原子整体替换。 */
@Service
public class AssistantService {
    private final AssistantRepository assistants;
    private final SkillRepository skills;
    private final MaterialRepository materials;
    private final AssistantSkillRepository assistantSkills;
    private final AssistantMaterialRepository assistantMaterials;

    public AssistantService(AssistantRepository assistants,
                            SkillRepository skills,
                            MaterialRepository materials,
                            AssistantSkillRepository assistantSkills,
                            AssistantMaterialRepository assistantMaterials) {
        this.assistants = assistants;
        this.skills = skills;
        this.materials = materials;
        this.assistantSkills = assistantSkills;
        this.assistantMaterials = assistantMaterials;
    }

    @Transactional(readOnly = true)
    public List<AssistantView> list(UserAccount user) {
        List<Assistant> visible = user.getRole() == Role.TEACHER
                ? assistants.findAllByClassIdOrderByCreatedAtAsc(user.getClassId())
                : assistants.findAllByClassIdAndEnabledTrueOrderByCreatedAtAsc(user.getClassId());
        return visible.stream().map(assistant -> view(assistant, user.getRole() == Role.TEACHER)).toList();
    }

    @Transactional(readOnly = true)
    public AssistantView get(Long id, UserAccount user) {
        Assistant assistant = user.getRole() == Role.TEACHER
                ? assistants.findByIdAndClassId(id, user.getClassId()).orElseThrow(NotFoundException::new)
                : assistants.findByIdAndClassIdAndEnabledTrue(id, user.getClassId()).orElseThrow(NotFoundException::new);
        return view(assistant, user.getRole() == Role.TEACHER);
    }

    @Transactional
    public AssistantView create(AssistantConfigRequest request, UserAccount teacher) {
        CleanConfig config = validate(request, teacher.getClassId(), null);
        Assistant assistant = new Assistant(teacher.getClassId(), config.name(), config.promptText(), config.enabled());
        try {
            assistants.saveAndFlush(assistant);
            replaceBindings(assistant.getId(), config.skillIds(), config.materialIds());
            return view(assistant, true);
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("本班已存在同名智能体", exception);
        }
    }

    @Transactional
    public AssistantView update(Long id, AssistantConfigRequest request, UserAccount teacher) {
        Assistant assistant = assistants.findByIdAndClassId(id, teacher.getClassId())
                .orElseThrow(NotFoundException::new);
        CleanConfig config = validate(request, teacher.getClassId(), id);
        assistant.update(config.name(), config.promptText(), config.enabled());
        try {
            assistants.saveAndFlush(assistant);
            replaceBindings(assistant.getId(), config.skillIds(), config.materialIds());
            return view(assistant, true);
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("本班已存在同名智能体", exception);
        }
    }

    private CleanConfig validate(AssistantConfigRequest request, Long classId, Long currentId) {
        if (request == null || request.enabled() == null) {
            throw new BadRequestException("必须指定智能体启用状态");
        }
        String name = request.name() == null ? "" : request.name().trim();
        String prompt = request.promptText() == null ? "" : request.promptText().trim();
        if (name.isBlank() || codePoints(name) > 64) {
            throw new BadRequestException("智能体名称必须包含 1 至 64 个字符");
        }
        if (codePoints(prompt) > 2000) {
            throw new BadRequestException("角色提示词不能超过 2000 字");
        }
        boolean duplicate = currentId == null
                ? assistants.findFirstByClassIdAndName(classId, name).isPresent()
                : assistants.existsByClassIdAndNameAndIdNot(classId, name, currentId);
        if (duplicate) {
            throw new BadRequestException("本班已存在同名智能体");
        }
        Set<Long> skillIds = normalizedIds(request.skillIds());
        Set<Long> materialIds = normalizedIds(request.materialIds());
        if (skills.findAllByIdInAndClassId(skillIds, classId).size() != skillIds.size()
                || materials.findAllByIdInAndClassId(materialIds, classId).size() != materialIds.size()) {
            throw new NotFoundException();
        }
        return new CleanConfig(name, prompt, request.enabled(), skillIds, materialIds);
    }

    private Set<Long> normalizedIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        if (ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw new NotFoundException();
        }
        return new LinkedHashSet<>(ids);
    }

    private void replaceBindings(Long assistantId, Set<Long> skillIds, Set<Long> materialIds) {
        assistantSkills.deleteAllByAssistantId(assistantId);
        assistantMaterials.deleteAllByAssistantId(assistantId);
        assistantSkills.flush();
        assistantMaterials.flush();
        assistantSkills.saveAll(skillIds.stream().map(id -> new AssistantSkill(assistantId, id)).toList());
        assistantMaterials.saveAll(materialIds.stream().map(id -> new AssistantMaterial(assistantId, id)).toList());
        assistantSkills.flush();
        assistantMaterials.flush();
    }

    private AssistantView view(Assistant assistant, boolean teacher) {
        if (!teacher) {
            return AssistantView.student(assistant);
        }
        Set<Long> skillIds = assistantSkills.findAllByAssistantId(assistant.getId()).stream()
                .map(AssistantSkill::getSkillId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Long> materialIds = assistantMaterials.findAllByAssistantId(assistant.getId()).stream()
                .map(AssistantMaterial::getMaterialId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return AssistantView.teacher(assistant, skillIds, materialIds);
    }

    private int codePoints(String value) { return value.codePointCount(0, value.length()); }

    private record CleanConfig(String name, String promptText, boolean enabled,
                               Set<Long> skillIds, Set<Long> materialIds) {
    }
}
