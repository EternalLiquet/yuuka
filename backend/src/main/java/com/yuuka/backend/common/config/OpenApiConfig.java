package com.yuuka.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
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

  @Bean
  public OpenApiCustomizer publicHealthOperations() {
    List<String> publicPaths =
        List.of("/health", "/health/live", "/health/ready", "/health/version");
    return openApi ->
        publicPaths.forEach(
            path -> {
              if (openApi.getPaths().containsKey(path)) {
                openApi
                    .getPaths()
                    .get(path)
                    .readOperations()
                    .forEach(operation -> operation.setSecurity(List.of()));
              }
            });
  }

  @Bean
  public OpenApiCustomizer spendingBucketRollingDaysParameter() {
    return openApi -> {
      var path = openApi.getPaths().get("/api/v1/spending-buckets/performance/rolling");
      if (path == null || path.getGet() == null || path.getGet().getParameters() == null) {
        return;
      }
      path.getGet().getParameters().stream()
          .filter(parameter -> "days".equals(parameter.getName()))
          .findFirst()
          .ifPresent(
              parameter ->
                  parameter.setSchema(
                      new IntegerSchema()
                          .format("int32")
                          .addEnumItem(30)
                          .addEnumItem(90)
                          ._default(30)));
    };
  }
}
