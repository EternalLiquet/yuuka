package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.common.api.ApplicationVersion;
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
  @Autowired private ApplicationVersion applicationVersion;

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
    assertThat(response.path("version").asText()).isEqualTo(applicationVersion.version());
  }

  @Test
  void legacyHealthRouteUsesSameLivenessContract() throws Exception {
    mockMvc
        .perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.version").value(applicationVersion.version()));
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
  void versionReportsOnlyApplicationVersionWithoutAuthentication() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/health/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(applicationVersion.version()))
            .andReturn();

    assertThat(result.getResponse().getContentType()).contains("application/json");
    JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(response.fieldNames()).toIterable().containsExactly("version");
    assertThat(response.fieldNames())
        .toIterable()
        .doesNotContain(
            "status",
            "environment",
            "commit",
            "branch",
            "host",
            "hostname",
            "database",
            "datasource",
            "path",
            "dependencies");
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
    mockMvc.perform(get("/health")).andExpect(status().isOk());
    mockMvc.perform(get("/health/live")).andExpect(status().isOk());
    mockMvc.perform(get("/health/ready")).andExpect(status().isOk());
    mockMvc.perform(get("/health/version")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/paychecks/active")).andExpect(status().isUnauthorized());
  }
}
