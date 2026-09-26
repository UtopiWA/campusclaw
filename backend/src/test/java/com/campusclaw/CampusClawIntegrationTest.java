package com.campusclaw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusclaw.persistence.DemoSeedService;
import com.campusclaw.persistence.KnowledgeEntry;
import com.campusclaw.persistence.KnowledgeEntryRepository;
import com.campusclaw.persistence.MaterialRepository;
import com.campusclaw.persistence.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CampusClawIntegrationTest {
    private static final String DEMO_PASSWORD = "test-demo-password";
    private static Path uploadDir;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("campusclaw")
            .withUsername("campusclaw")
            .withPassword("integration-password");

    @BeforeAll
    static void createUploadDirectory() throws Exception {
        uploadDir = Files.createTempDirectory("campusclaw-integration-");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.upload-dir", () -> uploadDir.toString());
        registry.add("app.demo-seed.enabled", () -> true);
        registry.add("app.demo-seed.password", () -> DEMO_PASSWORD);
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserAccountRepository users;
    @Autowired MaterialRepository materials;
    @Autowired DemoSeedService seedService;
    @Autowired PasswordEncoder passwordEncoder;
    @SpyBean KnowledgeEntryRepository knowledgeEntries;

    @AfterEach
    void restoreSpy() {
        reset(knowledgeEntries);
    }

    @Test
    void healthIsPublicAndBusinessApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        mockMvc.perform(get("/api/materials"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("班级"))));
        mockMvc.perform(get("/api/materials/1/content"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginUsesBcryptAndSeedIsIdempotent() throws Exception {
        var teacher = users.findByUsername("teacher-a").orElseThrow();
        assertThat(teacher.getPasswordHash()).isNotEqualTo(DEMO_PASSWORD);
        assertThat(passwordEncoder.matches(DEMO_PASSWORD, teacher.getPasswordHash())).isTrue();
        long userCount = users.count();
        long materialCount = materials.count();
        seedService.seed(DEMO_PASSWORD);
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(materials.count()).isEqualTo(materialCount);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"teacher-a\",\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"teacher-a\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("teacher-a"))));
    }

    @Test
    void enforcesClassIsolationRolesAndUploadLifecycle() throws Exception {
        MockHttpSession teacherSession = login("teacher-a");
        MockHttpSession studentASession = login("student-a1");
        MockHttpSession studentBSession = login("student-b1");

        MvcResult bList = mockMvc.perform(get("/api/materials").session(studentBSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("B 班数学教研示例"))
                .andExpect(jsonPath("$[0].hasFile").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("storedPath"))))
                .andExpect(jsonPath("$[*].title", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("A 班语文教研示例"))))
                .andReturn();
        long bMaterialId = ((Number) com.jayway.jsonpath.JsonPath.read(
                bList.getResponse().getContentAsString(StandardCharsets.UTF_8), "$[0].id")).longValue();

        mockMvc.perform(get("/api/materials").session(teacherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("A 班语文教研示例"))
                .andExpect(jsonPath("$[*].title", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("B 班数学教研示例"))));
        mockMvc.perform(get("/api/materials/{id}", bMaterialId).session(teacherSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Resource not found"));
        mockMvc.perform(get("/api/materials/{id}/content", bMaterialId).session(teacherSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/materials/{id}/download", bMaterialId).session(teacherSession))
                .andExpect(status().isNotFound());

        MockMultipartFile studentFile = new MockMultipartFile(
                "file", "student.md", "text/markdown", "不应写入".getBytes(StandardCharsets.UTF_8));
        long beforeStudentWrite = materials.count();
        mockMvc.perform(multipart("/api/materials/upload").file(studentFile).session(studentASession).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(materials.count()).isEqualTo(beforeStudentWrite);

        byte[] uploadedBytes = "第一段\n\n第二段".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile validFile = new MockMultipartFile(
                "file", "lesson.md", "text/markdown", uploadedBytes);
        MvcResult upload = mockMvc.perform(multipart("/api/materials/upload")
                        .file(validFile).session(teacherSession).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.material.title").value("lesson"))
                .andExpect(jsonPath("$.material.hasFile").value(true))
                .andExpect(jsonPath("$.material.fileSizeBytes").value(uploadedBytes.length))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("storedPath"))))
                .andReturn();
        long uploadedId = ((Number) com.jayway.jsonpath.JsonPath.read(
                upload.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.materialId")).longValue();
        Path uploadedPath = uploadDir.resolve(materials.findById(uploadedId).orElseThrow().getStoredPath());
        assertThat(Files.exists(uploadedPath)).isTrue();

        mockMvc.perform(get("/api/materials/{id}/content", uploadedId).session(studentASession))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().bytes(uploadedBytes));
        mockMvc.perform(get("/api/materials/{id}/download", uploadedId).session(teacherSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("lesson.md")))
                .andExpect(content().bytes(uploadedBytes));
        mockMvc.perform(get("/api/materials/{id}/download", uploadedId).session(studentBSession))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/materials/{id}", uploadedId).session(teacherSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"更新后的材料\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("更新后的材料"));
        mockMvc.perform(patch("/api/materials/{id}", uploadedId).session(teacherSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"伪造\",\"class_id\":999}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/materials/{id}", uploadedId).session(teacherSession).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(materials.findById(uploadedId)).isEmpty();
        assertThat(Files.exists(uploadedPath)).isFalse();
        assertThat(knowledgeEntries.findAllByMaterialIdAndClassIdOrderByChunkIndex(
                uploadedId, users.findByUsername("teacher-a").orElseThrow().getClassId())).isEmpty();
    }

    @Test
    void rejectsInvalidFilesAndRollsBackDatabaseFailures() throws Exception {
        MockHttpSession teacherSession = login("teacher-a");
        long before = materials.count();
        long filesBefore = regularFileCount(uploadDir);

        MockMultipartFile invalid = new MockMultipartFile("file", "bad.pdf", "application/pdf", new byte[] {1, 2});
        mockMvc.perform(multipart("/api/materials/upload").file(invalid).session(teacherSession).with(csrf()))
                .andExpect(status().isBadRequest());
        MockMultipartFile unsafeName = new MockMultipartFile(
                "file", "bad\r\nname.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/materials/upload").file(unsafeName).session(teacherSession).with(csrf()))
                .andExpect(status().isBadRequest());
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        mockMvc.perform(multipart("/api/materials/upload").file(empty).session(teacherSession).with(csrf()))
                .andExpect(status().isBadRequest());
        MockMultipartFile malformed = new MockMultipartFile(
                "file", "bad.txt", "text/plain", new byte[] {(byte) 0xC3, (byte) 0x28});
        mockMvc.perform(multipart("/api/materials/upload").file(malformed).session(teacherSession).with(csrf()))
                .andExpect(status().isBadRequest());
        MockMultipartFile oversized = new MockMultipartFile(
                "file", "large.txt", "text/plain", new byte[2 * 1024 * 1024 + 1]);
        mockMvc.perform(multipart("/api/materials/upload").file(oversized).session(teacherSession).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(materials.count()).isEqualTo(before);
        assertThat(regularFileCount(uploadDir)).isEqualTo(filesBefore);

        doThrow(new DataIntegrityViolationException("injected database failure"))
                .when(knowledgeEntries).save(any(KnowledgeEntry.class));
        MockMultipartFile valid = new MockMultipartFile(
                "file", "rollback.txt", "text/plain", "必须回滚".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/materials/upload").file(valid).session(teacherSession).with(csrf()))
                .andExpect(status().is5xxServerError());
        assertThat(materials.count()).isEqualTo(before);
        assertThat(regularFileCount(uploadDir)).isEqualTo(filesBefore);
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        MockHttpSession session = login("teacher-a");
        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/materials").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void seedKnowledgeMaterialHasNoDownloadableFile() throws Exception {
        MockHttpSession teacherSession = login("teacher-a");
        long classId = users.findByUsername("teacher-a").orElseThrow().getClassId();
        long seedMaterialId = materials.findFirstByClassIdAndTitleAndStoredPathIsNull(
                classId, "A 班语文教研示例").orElseThrow().getId();

        mockMvc.perform(get("/api/materials/{id}/content", seedMaterialId).session(teacherSession))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/materials/{id}/download", seedMaterialId).session(teacherSession))
                .andExpect(status().isNotFound());
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + DEMO_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long regularFileCount(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }
}
