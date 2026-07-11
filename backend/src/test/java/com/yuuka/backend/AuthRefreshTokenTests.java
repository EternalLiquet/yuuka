package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AuthRefreshTokenTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void rotatesRefreshTokenAndRevokesTheFamilyWhenAnOldTokenIsReplayed() throws Exception {
    JsonNode session = register("refresh@yuuka.local");
    String original = session.path("refreshToken").asText();

    JsonNode rotated = refresh(original, 200);
    String replacement = rotated.path("refreshToken").asText();
    assertThat(replacement).isNotBlank().isNotEqualTo(original);

    refresh(original, 401);
    refresh(replacement, 401);
  }

  @Test
  void logoutRevokesThePresentedTokenAndRemainsIdempotent() throws Exception {
    String token = register("logout@yuuka.local").path("refreshToken").asText();

    String body = "{\"refreshToken\":\"" + token + "\"}";
    mockMvc
        .perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNoContent());
    refresh(token, 401);
  }

  private JsonNode register(String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"%s","password":"Password12345","displayName":"Test"}
                        """
                            .formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode refresh(String token, int expectedStatus) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"" + token + "\"}"))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
  }
}
