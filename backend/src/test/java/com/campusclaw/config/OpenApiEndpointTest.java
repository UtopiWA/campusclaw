package com.campusclaw.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campusclaw.agent.AssistantController;
import com.campusclaw.agent.AssistantService;
import com.campusclaw.agent.SkillController;
import com.campusclaw.agent.SkillService;
import com.campusclaw.auth.CurrentUserService;
import com.campusclaw.conversation.AgentConversationService;
import com.campusclaw.conversation.ConversationController;
import com.campusclaw.conversation.ConversationService;
import com.campusclaw.conversation.ConversationStreamingService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AssistantController.class, SkillController.class, ConversationController.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AsyncConfig.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({SpringDocConfigProperties.class, SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class})
class OpenApiEndpointTest {
    @Autowired
    private MockMvc mvc;

    @MockBean private CurrentUserService currentUsers;
    @MockBean private AppProperties appProperties;
    @MockBean private JwtProperties jwtProperties;
    @MockBean private AssistantService assistants;
    @MockBean private SkillService skills;
    @MockBean private ConversationService conversations;
    @MockBean private AgentConversationService agentConversations;
    @MockBean private ConversationStreamingService streaming;

    @Test
    void exposesAllNewPathsDtosAndSseMediaType() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.paths['/api/assistants'].get").exists())
                .andExpect(jsonPath("$.paths['/api/assistants'].post").exists())
                .andExpect(jsonPath("$.paths['/api/assistants/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/skills'].post").exists())
                .andExpect(jsonPath("$.paths['/api/skills/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/conversations'].post").exists())
                .andExpect(jsonPath("$.paths['/api/conversations/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/conversations/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/api/conversations/{id}/messages'].post.responses['200'].content['text/event-stream']").exists())
                .andExpect(jsonPath("$.components.schemas.AssistantConfigRequest").exists())
                .andExpect(jsonPath("$.components.schemas.SkillSourceRequest").exists())
                .andExpect(jsonPath("$.components.schemas.CreateConversationRequest").exists())
                .andExpect(jsonPath("$.components.schemas.SendMessageRequest").exists());
    }
}
