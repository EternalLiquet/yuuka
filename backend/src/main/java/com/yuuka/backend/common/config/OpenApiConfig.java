package com.yuuka.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  public OpenAPI yuukaOpenApi() {
    String scheme = "bearerAuth";
    return new OpenAPI()
        .info(
            new Info()
                .title("Yuuka API")
                .version("v1")
                .description("Private paycheck-first budgeting API."))
        .components(
            new Components()
                .addSecuritySchemes(
                    scheme,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList(scheme));
  }
}
