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
          "/api/v1/paychecks/from-template",
          "/api/v1/paybacks",
          "/api/v1/paybacks/{paybackId}",
          "/api/v1/paybacks/{paybackId}/repayments",
          "/api/v1/templates");

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

    Path generatedPath = Path.of("build", "generated", "openapi.json");
    Files.createDirectories(generatedPath.getParent());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(generatedPath.toFile(), generated);

    Path committedPath = Path.of("..", "docs", "openapi.json");
    assertThat(committedPath).exists();
    assertThat(objectMapper.readTree(committedPath.toFile())).isEqualTo(generated);
  }
}
