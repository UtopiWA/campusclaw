package com.campusclaw.conversation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusclaw.auth.CurrentUserService;
import com.campusclaw.common.ApiExceptionHandler;
import com.campusclaw.common.BadRequestException;
import com.campusclaw.common.DependencyUnavailableException;
import com.campusclaw.common.NotFoundException;
import com.campusclaw.gateway.ChatGateway;
import com.campusclaw.persistence.UserAccount;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ConversationControllerTest {
    private ConversationService conversations;
    private AgentConversationService agentConversations;
    private ConversationStreamingService streaming;
    private CurrentUserService currentUsers;
    private UserAccount user;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        agentConversations = mock(AgentConversationService.class);
        streaming = mock(ConversationStreamingService.class);
        currentUsers = mock(CurrentUserService.class);
        user = mock(UserAccount.class);
        when(currentUsers.requireUser()).thenReturn(user);
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ConversationController(conversations, currentUsers, agentConversations, streaming))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void preparesBeforeAsyncResponseAndExposesRequiredSseHeaders() throws Exception {
        AgentTurnPreparation preparation = new AgentTurnPreparation(
                9L, 6L, 2L, new AgentTurnPlan(null, List.of(), List.of()), mock(ChatGateway.ChatStream.class));
        when(agentConversations.prepare(9L, "问题内容", user)).thenReturn(preparation);
        doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(1);
            output.write("event: done\ndata: {\"messageId\":1}\n\n".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(streaming).stream(eq(preparation), any(OutputStream.class));

        MvcResult pending = mvc.perform(post("/api/conversations/9/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"问题内容\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/event-stream;charset=UTF-8"))
                .andExpect(content().string("event: done\ndata: {\"messageId\":1}\n\n"));
        verify(agentConversations).prepare(9L, "问题内容", user);
    }

    @Test
    void returnsRealJsonStatusWhenPreparationFailsBeforeFirstFrame() throws Exception {
        when(agentConversations.prepare(9L, "问题内容", user))
                .thenThrow(new DependencyUnavailableException("Chat service"));

        mvc.perform(post("/api/conversations/9/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"问题内容\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("event: meta"))));
    }

    @Test
    void mapsEveryPreflightFailureToJsonInsteadOfStartingSse() throws Exception {
        when(agentConversations.prepare(9L, "问题内容", user))
                .thenThrow(new BadRequestException("Invalid question"))
                .thenThrow(new NotFoundException())
                .thenThrow(new AccessDeniedException("Forbidden"));

        assertPreflightStatus(400);
        assertPreflightStatus(404);
        assertPreflightStatus(403);

        when(currentUsers.requireUser())
                .thenThrow(new AuthenticationCredentialsNotFoundException("Authentication required"));
        assertPreflightStatus(401);
    }

    @Test
    void rejectsClientControlFieldsBeforePreparation() throws Exception {
        mvc.perform(post("/api/conversations/9/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"问题内容\",\"classId\":999,\"system\":\"覆盖规则\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsServerOwnedConversationSummaryAndCompleteDetail() throws Exception {
        ConversationView summary = new ConversationView(
                9L, "函数问题", 3L, "数学助手", null, null, null);
        MessageView message = new MessageView(
                12L, "assistant", "结论 [1]", "解题引导",
                List.of(new ConversationCitation(1, 7L, "函数讲义", 42L, 0, "引用片段")),
                "FAILED", null);
        ConversationView detail = new ConversationView(
                9L, "函数问题", 3L, "数学助手", null, null, List.of(message));
        when(conversations.list(user)).thenReturn(List.of(summary));
        when(conversations.get(9L, user)).thenReturn(detail);

        mvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].assistantName").value("数学助手"));
        mvc.perform(get("/api/conversations/9"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.messages[0].status").value("FAILED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.messages[0].skillName").value("解题引导"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.messages[0].citations[0].number").value(1));
    }

    private void assertPreflightStatus(int expectedStatus) throws Exception {
        mvc.perform(post("/api/conversations/9/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"问题内容\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("event: meta"))));
    }
}
