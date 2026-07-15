package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OpenApiContractTests extends AbstractIntegrationTest {
  private static final Set<String> REQUIRED_PATHS =
      Set.of(
          "/api/v1/auth/login",
          "/api/v1/auth/refresh",
          "/api/v1/auth/logout",
          "/api/v1/me",
          "/api/v1/paychecks/active",
          "/api/v1/paychecks/history",
          "/api/v1/paychecks",
          "/api/v1/spending-buckets/performance/rolling",
          "/api/v1/spending-buckets/performance/rolling-90-days",
          "/api/v1/paychecks/from-template",
          "/api/v1/search/entries",
          "/api/v1/paybacks",
          "/api/v1/paybacks/{paybackId}",
          "/api/v1/paybacks/{paybackId}/repayments",
          "/api/v1/templates",
          "/health/live",
          "/health/version");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @WithMockUser
  void generatedContractContainsRequiredApiAndMatchesCommittedSnapshot() throws Exception {
    String content =
        mockMvc
            .perform(get("/api/openapi"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode generated = objectMapper.readTree(content);
    assertThat(generated.path("openapi").asText()).startsWith("3.");
    assertThat(generated.path("paths").fieldNames()).toIterable().containsAll(REQUIRED_PATHS);
    JsonNode versionOperation = generated.path("paths").path("/health/version").path("get");
    JsonNode versionSchema =
        versionOperation
            .path("responses")
            .path("200")
            .path("content")
            .path("application/json")
            .path("schema");
    assertThat(versionSchema.path("$ref").asText())
        .isEqualTo("#/components/schemas/VersionResponse");
    assertThat(versionOperation.path("security").isArray()).isTrue();
    assertThat(versionOperation.path("security").size()).isZero();
    JsonNode versionResponse = generated.path("components").path("schemas").path("VersionResponse");
    assertThat(versionResponse.path("required").size()).isEqualTo(1);
    assertThat(versionResponse.path("required").get(0).asText()).isEqualTo("version");
    assertThat(versionResponse.path("properties").fieldNames())
        .toIterable()
        .containsExactly("version");
    assertThat(versionResponse.path("properties").path("version").path("type").asText())
        .isEqualTo("string");
    assertThat(versionResponse.path("properties").path("version").path("minLength").asInt())
        .isEqualTo(1);
    JsonNode rollingParameters =
        generated
            .path("paths")
            .path("/api/v1/spending-buckets/performance/rolling")
            .path("get")
            .path("parameters");
    assertThat(rollingParameters.size()).isEqualTo(2);
    assertThat(rollingParameters.findValuesAsText("name")).containsExactly("days", "asOfDate");
    assertThat(rollingParameters.get(0).path("required").asBoolean()).isFalse();
    assertThat(rollingParameters.get(0).path("schema").path("type").asText()).isEqualTo("integer");
    assertThat(rollingParameters.get(0).path("schema").path("enum"))
        .extracting(JsonNode::asText)
        .containsExactly("30", "90");
    assertThat(rollingParameters.get(1).path("required").asBoolean()).isFalse();

    JsonNode compatibilityParameters =
        generated
            .path("paths")
            .path("/api/v1/spending-buckets/performance/rolling-90-days")
            .path("get")
            .path("parameters");
    assertThat(compatibilityParameters.size()).isEqualTo(1);
    assertThat(compatibilityParameters.get(0).path("name").asText()).isEqualTo("asOfDate");
    assertThat(compatibilityParameters.get(0).path("required").asBoolean()).isFalse();

    Path generatedPath = Path.of("build", "generated", "openapi.json");
    Files.createDirectories(generatedPath.getParent());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(generatedPath.toFile(), generated);

    Path committedPath = Path.of("..", "docs", "openapi.json");
    assertThat(committedPath).exists();
    assertThat(objectMapper.readTree(committedPath.toFile())).isEqualTo(generated);
  }
}
