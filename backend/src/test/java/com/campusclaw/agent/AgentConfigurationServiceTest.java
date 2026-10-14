package com.campusclaw.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.persistence.Assistant;
import com.campusclaw.persistence.AssistantMaterialRepository;
import com.campusclaw.persistence.AssistantRepository;
import com.campusclaw.persistence.AssistantSkillRepository;
import com.campusclaw.persistence.Material;
import com.campusclaw.persistence.MaterialRepository;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.SkillRepository;
import com.campusclaw.persistence.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentConfigurationServiceTest {

    @Test
    void invalidSkillSourceDoesNotReadOrMutatePersistence() {
        SkillRepository skills = mock(SkillRepository.class);
        SkillService service = new SkillService(skills, new SkillTextParser());
        UserAccount teacher = mock(UserAccount.class);

        assertThatThrownBy(() -> service.update(
                9L, "名称：重复\n名称：重复\n适用条件：测试\n启用：是\n\n说明", teacher))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(skills, teacher);
    }

    @Test
    void skillUpdateChangesAllParsedFieldsOnlyAfterValidation() {
        SkillRepository skills = mock(SkillRepository.class);
        Skill skill = mock(Skill.class);
        UserAccount teacher = mock(UserAccount.class);
        when(teacher.getClassId()).thenReturn(3L);
        when(skills.findByIdAndClassId(7L, 3L)).thenReturn(Optional.of(skill));
        when(skills.saveAndFlush(skill)).thenReturn(skill);
        when(skill.getId()).thenReturn(7L);
        when(skill.getName()).thenReturn("更新后");
        when(skill.getTriggerText()).thenReturn("方程,分数");
        when(skill.getMaterialScope()).thenReturn("数学");
        when(skill.getToolName()).thenReturn("knowledge_search");
        when(skill.isEnabled()).thenReturn(true);
        when(skill.getInstructions()).thenReturn("只提示下一步。");
        when(skill.getSourceText()).thenReturn(validSkillSource("更新后"));
        SkillService service = new SkillService(skills, new SkillTextParser());

        SkillView view = service.update(7L, validSkillSource("更新后"), teacher);

        assertThat(view.name()).isEqualTo("更新后");
        verify(skill).update("更新后", "方程,分数", "数学", "knowledge_search", true,
                "只提示下一步。", validSkillSource("更新后"));
        verify(skills).saveAndFlush(skill);
    }

    @Test
    void assistantRejectsCrossClassBindingBeforeChangingAnything() {
        AssistantRepository assistants = mock(AssistantRepository.class);
        SkillRepository skills = mock(SkillRepository.class);
        MaterialRepository materials = mock(MaterialRepository.class);
        AssistantSkillRepository assistantSkills = mock(AssistantSkillRepository.class);
        AssistantMaterialRepository assistantMaterials = mock(AssistantMaterialRepository.class);
        Assistant assistant = mock(Assistant.class);
        UserAccount teacher = mock(UserAccount.class);
        when(teacher.getClassId()).thenReturn(1L);
        when(assistants.findByIdAndClassId(5L, 1L)).thenReturn(Optional.of(assistant));
        when(skills.findAllByIdInAndClassId(Set.of(8L), 1L)).thenReturn(List.of(mock(Skill.class)));
        when(materials.findAllByIdInAndClassId(Set.of(12L), 1L)).thenReturn(List.of());
        AssistantService service = new AssistantService(
                assistants, skills, materials, assistantSkills, assistantMaterials);

        assertThatThrownBy(() -> service.update(5L,
                new AssistantConfigRequest("新名称", "提示", true, Set.of(8L), Set.of(12L)), teacher))
                .isInstanceOf(NotFoundException.class);

        verify(assistant, never()).update(any(), any(), any(Boolean.class));
        verifyNoInteractions(assistantSkills, assistantMaterials);
    }

    @Test
    void assistantAtomicallyReplacesValidatedBindings() {
        AssistantRepository assistants = mock(AssistantRepository.class);
        SkillRepository skills = mock(SkillRepository.class);
        MaterialRepository materials = mock(MaterialRepository.class);
        AssistantSkillRepository assistantSkills = mock(AssistantSkillRepository.class);
        AssistantMaterialRepository assistantMaterials = mock(AssistantMaterialRepository.class);
        Assistant assistant = mock(Assistant.class);
        UserAccount teacher = mock(UserAccount.class);
        when(teacher.getClassId()).thenReturn(1L);
        when(assistants.findByIdAndClassId(5L, 1L)).thenReturn(Optional.of(assistant));
        when(assistant.getId()).thenReturn(5L);
        when(assistant.getName()).thenReturn("新名称");
        when(assistant.getPromptText()).thenReturn("提示");
        when(assistant.isEnabled()).thenReturn(true);
        when(skills.findAllByIdInAndClassId(Set.of(8L), 1L)).thenReturn(List.of(mock(Skill.class)));
        when(materials.findAllByIdInAndClassId(Set.of(12L), 1L)).thenReturn(List.of(mock(Material.class)));
        AssistantService service = new AssistantService(
                assistants, skills, materials, assistantSkills, assistantMaterials);

        AssistantView view = service.update(5L,
                new AssistantConfigRequest("新名称", "提示", true, Set.of(8L), Set.of(12L)), teacher);

        assertThat(view.name()).isEqualTo("新名称");
        verify(assistant).update("新名称", "提示", true);
        verify(assistantSkills).deleteAllByAssistantId(5L);
        verify(assistantMaterials).deleteAllByAssistantId(5L);
        verify(assistantSkills).saveAll(any());
        verify(assistantMaterials).saveAll(any());
    }

    private static String validSkillSource(String name) {
        return "名称：" + name + "\n适用条件：方程,分数\n资料：数学\n工具：knowledge_search\n启用：是\n\n"
                + "只提示下一步。";
    }
}
