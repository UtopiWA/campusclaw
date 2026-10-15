package com.campusclaw.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusclaw.auth.CurrentUserService;
import com.campusclaw.common.ApiExceptionHandler;
import com.campusclaw.common.ConflictException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.persistence.UserAccount;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentConfigurationControllerTest {
    private AssistantService assistants;
    private SkillService skills;
    private CurrentUserService currentUsers;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        assistants = mock(AssistantService.class);
        skills = mock(SkillService.class);
        currentUsers = mock(CurrentUserService.class);
        ObjectMapper strictMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AssistantController(assistants, currentUsers),
                        new SkillController(skills, currentUsers))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(strictMapper))
                .build();
    }

    @Test
    void mapsMissingAuthenticationAndStudentWriteToStableStatuses() throws Exception {
        when(currentUsers.requireUser())
                .thenThrow(new AuthenticationCredentialsNotFoundException("Authentication required"));
        mvc.perform(get("/api/assistants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));

        when(currentUsers.requireTeacher()).thenThrow(new AccessDeniedException("Teacher role required"));
        mvc.perform(post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceText\":\"合法但学生无权写入\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
        verifyNoInteractions(skills);
    }

    @Test
    void exposesStudentSummaryWithoutPromptOrBindings() throws Exception {
        UserAccount student = mock(UserAccount.class);
        when(currentUsers.requireUser()).thenReturn(student);
        when(assistants.list(student)).thenReturn(List.of(
                new AssistantView(3L, "本班助手", true, null, null, null)));

        mvc.perform(get("/api/assistants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("本班助手"))
                .andExpect(jsonPath("$[0].promptText").doesNotExist())
                .andExpect(jsonPath("$[0].skillIds").doesNotExist())
                .andExpect(jsonPath("$[0].materialIds").doesNotExist());
    }

    @Test
    void mapsInvisibleResourceTo404AndCreatesTeacherConfiguration() throws Exception {
        UserAccount teacher = mock(UserAccount.class);
        when(currentUsers.requireUser()).thenReturn(teacher);
        when(currentUsers.requireTeacher()).thenReturn(teacher);
        when(assistants.get(99L, teacher)).thenThrow(new NotFoundException());
        when(assistants.create(any(), any())).thenReturn(
                new AssistantView(8L, "新助手", true, "提示", Set.of(2L), Set.of(4L)));

        mvc.perform(get("/api/assistants/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Resource not found"));
        mvc.perform(post("/api/assistants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新助手","promptText":"提示","enabled":true,
                                 "skillIds":[2],"materialIds":[4]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(8))
                .andExpect(jsonPath("$.skillIds[0]").value(2));
    }

    @Test
    void rejectsUnknownControlFieldsBeforeServiceMutation() throws Exception {
        when(currentUsers.requireTeacher()).thenReturn(mock(UserAccount.class));

        mvc.perform(put("/api/assistants/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"助手","promptText":"","enabled":true,
                                 "skillIds":[],"materialIds":[],"classId":999}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(assistants);
    }

    @Test
    void deletesUnusedAssistantAndMapsConversationConflict() throws Exception {
        UserAccount teacher = mock(UserAccount.class);
        when(currentUsers.requireTeacher()).thenReturn(teacher);

        mvc.perform(delete("/api/assistants/3"))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(assistants).delete(3L, teacher);
        org.mockito.Mockito.doThrow(new ConflictException("该智能体已有会话记录，请停用智能体以保留历史会话"))
                .when(assistants).delete(4L, teacher);
        mvc.perform(delete("/api/assistants/4"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("该智能体已有会话记录，请停用智能体以保留历史会话"));
    }
}
