package com.campusclaw.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /** 描述公开的 API 元数据与 Bearer JWT 认证方式，不写入任何真实凭据。 */
    @Bean
    OpenAPI campusClawOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CampusClaw API")
                        .version("v1")
                        .description("中小学教研智能体平台接口；业务接口均使用 Bearer JWT。"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
