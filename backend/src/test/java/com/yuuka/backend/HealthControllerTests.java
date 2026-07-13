package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class HealthControllerTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void livenessReportsStatusAndVersionWithoutAuthentication() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/health/live"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.version").isNotEmpty())
            .andReturn();

    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(response.path("version").asText()).isEqualTo("0.0.0-dev");
  }

  @Test
  void legacyHealthRouteUsesSameLivenessContract() throws Exception {
    mockMvc
        .perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.version").value("0.0.0-dev"));
  }

  @Test
  void livenessDoesNotExposeUnsafeOperationalDetails() throws Exception {
    MvcResult result = mockMvc.perform(get("/health/live")).andExpect(status().isOk()).andReturn();

    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(response.fieldNames()).toIterable().containsExactlyInAnyOrder("status", "version");
    assertThat(response.fieldNames())
        .toIterable()
        .doesNotContain(
            "environment",
            "secrets",
            "database",
            "datasource",
            "jwt",
            "totp",
            "host",
            "hostname",
            "path",
            "stackTrace");
  }

  @Test
  void readinessStillChecksDependenciesAndDoesNotExposeVersion() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/health/ready"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andReturn();

    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(response.fieldNames()).toIterable().containsExactlyInAnyOrder("status");
  }

  @Test
  void healthEndpointsArePublicButPrivateApiStillRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/health/live")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }
}
