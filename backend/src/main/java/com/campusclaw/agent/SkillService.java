package com.campusclaw.agent;

import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.persistence.Role;
import com.campusclaw.persistence.McpServer;
import com.campusclaw.persistence.McpServerRepository;
import com.campusclaw.persistence.McpToolRepository;
import com.campusclaw.persistence.Skill;
import com.campusclaw.persistence.SkillRepository;
import com.campusclaw.persistence.UserAccount;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理班级技能原文与结构化字段，所有写入都在完整解析后发生。 */
@Service
public class SkillService {
    private final SkillRepository skills;
    private final SkillTextParser parser;
    private final McpServerRepository mcpServers;
    private final McpToolRepository mcpTools;

    public SkillService(SkillRepository skills, SkillTextParser parser,
                        McpServerRepository mcpServers, McpToolRepository mcpTools) {
        this.skills = skills;
        this.parser = parser;
        this.mcpServers = mcpServers;
        this.mcpTools = mcpTools;
    }

    @Transactional(readOnly = true)
    public List<SkillView> list(UserAccount user) {
        boolean editable = user.getRole() == Role.TEACHER;
        return skills.findAllByClassIdOrderByCreatedAtAsc(user.getClassId()).stream()
                .map(skill -> SkillView.from(skill, editable))
                .toList();
    }

    @Transactional(readOnly = true)
    public SkillView get(Long id, UserAccount user) {
        return SkillView.from(requireSkill(id, user.getClassId()), user.getRole() == Role.TEACHER);
    }

    @Transactional
    public SkillView create(String sourceText, UserAccount teacher) {
        SkillDefinition definition = parser.parse(sourceText);
        validateToolRefs(definition.toolRefs(), teacher.getClassId());
        if (skills.findFirstByClassIdAndName(teacher.getClassId(), definition.name()).isPresent()) {
            throw new BadRequestException("本班已存在同名技能");
        }
        Skill skill = new Skill(
                teacher.getClassId(), definition.name(), definition.triggerText(), definition.materialScope(),
                definition.toolRefsText(), definition.enabled(), definition.instructions(), definition.sourceText());
        try {
            return SkillView.from(skills.saveAndFlush(skill), true);
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("本班已存在同名技能", exception);
        }
    }

    @Transactional
    public SkillView update(Long id, String sourceText, UserAccount teacher) {
        SkillDefinition definition = parser.parse(sourceText);
        Skill skill = requireSkill(id, teacher.getClassId());
        validateToolRefs(definition.toolRefs(), teacher.getClassId());
        if (skills.existsByClassIdAndNameAndIdNot(teacher.getClassId(), definition.name(), id)) {
            throw new BadRequestException("本班已存在同名技能");
        }
        skill.update(definition.name(), definition.triggerText(), definition.materialScope(), definition.toolRefsText(),
                definition.enabled(), definition.instructions(), definition.sourceText());
        try {
            return SkillView.from(skills.saveAndFlush(skill), true);
        } catch (DataIntegrityViolationException exception) {
            throw new BadRequestException("本班已存在同名技能", exception);
        }
    }

    @Transactional
    public void delete(Long id, UserAccount teacher) {
        skills.delete(requireSkill(id, teacher.getClassId()));
        skills.flush();
    }

    private Skill requireSkill(Long id, Long classId) {
        return skills.findByIdAndClassId(id, classId).orElseThrow(NotFoundException::new);
    }

    private void validateToolRefs(List<String> refs, Long classId) {
        for (String ref : refs) {
            if ("knowledge_search".equals(ref)) continue;
            int separator = ref.indexOf('@');
            String toolName = ref.substring(0, separator);
            String serverName = ref.substring(separator + 1);
            McpServer server = mcpServers.findFirstByClassIdAndName(classId, serverName)
                    .filter(McpServer::isConnected)
                    .orElseThrow(() -> new BadRequestException("工具不可用"));
            if (mcpTools.findFirstByServerIdAndName(server.getId(), toolName).isEmpty()) {
                throw new BadRequestException("工具不可用");
            }
        }
    }
}
