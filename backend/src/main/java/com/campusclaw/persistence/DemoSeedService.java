package com.campusclaw.persistence;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoSeedService {
    private final ClassRoomRepository classRooms;
    private final UserAccountRepository users;
    private final MaterialRepository materials;
    private final KnowledgeEntryRepository knowledgeEntries;
    private final AssignmentRepository assignments;
    private final AssistantRepository assistants;
    private final SkillRepository skills;
    private final PasswordEncoder passwordEncoder;

    public DemoSeedService(
            ClassRoomRepository classRooms,
            UserAccountRepository users,
            MaterialRepository materials,
            KnowledgeEntryRepository knowledgeEntries,
            AssignmentRepository assignments,
            AssistantRepository assistants,
            SkillRepository skills,
            PasswordEncoder passwordEncoder) {
        this.classRooms = classRooms;
        this.users = users;
        this.materials = materials;
        this.knowledgeEntries = knowledgeEntries;
        this.assignments = assignments;
        this.assistants = assistants;
        this.skills = skills;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void seed(String password) {
        ClassRoom classA = classRooms.findByName("班级 A").orElseGet(() -> classRooms.save(new ClassRoom("班级 A")));
        ClassRoom classB = classRooms.findByName("班级 B").orElseGet(() -> classRooms.save(new ClassRoom("班级 B")));

        UserAccount teacherA = createUser("teacher-a", password, Role.TEACHER, classA.getId());
        createUser("student-a1", password, Role.STUDENT, classA.getId());
        UserAccount studentB = createUser("student-b1", password, Role.STUDENT, classB.getId());

        createSeedMaterial(classA.getId(), teacherA.getId(), "A 班语文教研示例", "A 班示例知识条目");
        createSeedMaterial(classB.getId(), studentB.getId(), "B 班数学教研示例", "B 班示例知识条目");

        assignments.findFirstByClassIdAndTitle(classA.getId(), "A 班示例作业")
                .orElseGet(() -> assignments.save(new Assignment(classA.getId(), "A 班示例作业")));
        assignments.findFirstByClassIdAndTitle(classB.getId(), "B 班示例作业")
                .orElseGet(() -> assignments.save(new Assignment(classB.getId(), "B 班示例作业")));

        Assistant assistantA = assistants.findFirstByClassIdAndName(classA.getId(), "A 班教研助手")
                .orElseGet(() -> assistants.save(new Assistant(classA.getId(), "A 班教研助手")));
        Assistant assistantB = assistants.findFirstByClassIdAndName(classB.getId(), "B 班教研助手")
                .orElseGet(() -> assistants.save(new Assistant(classB.getId(), "B 班教研助手")));
        skills.findFirstByAssistantIdAndName(assistantA.getId(), "材料整理")
                .orElseGet(() -> skills.save(new Skill(assistantA.getId(), classA.getId(), "材料整理")));
        skills.findFirstByAssistantIdAndName(assistantB.getId(), "材料整理")
                .orElseGet(() -> skills.save(new Skill(assistantB.getId(), classB.getId(), "材料整理")));
    }

    private UserAccount createUser(String username, String password, Role role, Long classId) {
        return users.findByUsername(username)
                .orElseGet(() -> users.save(new UserAccount(username, passwordEncoder.encode(password), role, classId)));
    }

    private void createSeedMaterial(Long classId, Long uploaderId, String title, String body) {
        materials.findFirstByClassIdAndTitleAndStoredPathIsNull(classId, title).orElseGet(() -> {
            Material material = materials.save(new Material(classId, title, null, null, uploaderId));
            knowledgeEntries.save(new KnowledgeEntry(material.getId(), classId, 0, body));
            return material;
        });
    }
}

