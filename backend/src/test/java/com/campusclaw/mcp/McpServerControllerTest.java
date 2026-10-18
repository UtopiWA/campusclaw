package com.campusclaw.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusclaw.auth.CurrentUserService;
import com.campusclaw.common.ApiExceptionHandler;
import com.campusclaw.persistence.UserAccount;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class McpServerControllerTest {
    private McpServerService service;
    private CurrentUserService currentUsers;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(McpServerService.class);
        currentUsers = mock(CurrentUserService.class);
        ObjectMapper strictMapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new McpServerController(service, currentUsers))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(strictMapper))
                .build();
    }

    @Test
    void rejectsStudentsBeforeReadingOrChangingMcpState() throws Exception {
        when(currentUsers.requireTeacher()).thenThrow(new AccessDeniedException("Teacher role required"));

        mvc.perform(get("/api/mcp/servers"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\",\"baseUrl\":\"http://mcp-demo:8090\",\"token\":\"secret\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void createsAServiceWithoutReturningItsToken() throws Exception {
        UserAccount teacher = mock(UserAccount.class);
        when(currentUsers.requireTeacher()).thenReturn(teacher);
        when(service.create(any(), any())).thenReturn(new McpServerView(
                12L, "demo", "http://mcp-demo:8090/mcp", false, null, true, 0, null, null));

        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\",\"baseUrl\":\"http://mcp-demo:8090\",\"token\":\"secret\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasAuth").value(true))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void rejectsUnknownClientControlledFieldsBeforeMutation() throws Exception {
        when(currentUsers.requireTeacher()).thenReturn(mock(UserAccount.class));

        mvc.perform(post("/api/mcp/servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\",\"baseUrl\":\"http://mcp-demo:8090\","
                                + "\"token\":\"secret\",\"classId\":999}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
