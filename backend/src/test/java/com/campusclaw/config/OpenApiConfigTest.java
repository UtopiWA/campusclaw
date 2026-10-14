package com.campusclaw.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusclaw.agent.AssistantController;
import com.campusclaw.agent.SkillController;
import com.campusclaw.conversation.ConversationController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;

class OpenApiConfigTest {

    @Test
    void definesBearerJwtWithoutEmbeddingCredentialsOrInternalAddresses() {
        OpenAPI openAPI = new OpenApiConfig().campusClawOpenApi();
        SecurityScheme scheme = openAPI.getComponents().getSecuritySchemes().get("bearerAuth");

        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
        assertThat(openAPI.getInfo().toString())
                .doesNotContain("token", "secret", "password", "127.0.0.1", "jdbc:");
    }

    @Test
    void documentsEveryNewControllerOperationAndTheSseMediaType() {
        for (Class<?> controller : new Class<?>[] {
                AssistantController.class, SkillController.class, ConversationController.class}) {
            assertThat(controller.getAnnotation(SecurityRequirement.class).name()).isEqualTo("bearerAuth");
            assertThat(Arrays.stream(controller.getDeclaredMethods())
                    .filter(this::isEndpoint)
                    .allMatch(method -> method.isAnnotationPresent(Operation.class))).isTrue();
        }

        Method send = Arrays.stream(ConversationController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("send"))
                .findFirst()
                .orElseThrow();
        assertThat(send.getAnnotation(PostMapping.class).produces())
                .containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(send.getAnnotation(Operation.class).description())
                .contains("meta", "delta", "done", "error");
    }

    private boolean isEndpoint(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(annotation -> annotation.annotationType().getPackageName()
                        .equals("org.springframework.web.bind.annotation"));
    }
}
