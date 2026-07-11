package com.yuuka.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuuka.backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class AuthControllerTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void registersUserAndReturnsJwt() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .with(
                    request -> {
                      request.setRemoteAddr("192.0.2.10");
                      return request;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "foundation-test@yuuka.local",
                      "password": "Password12345",
                      "displayName": "Foundation Test"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.accessToken").isString())
        .andExpect(jsonPath("$.tokenType").value("Bearer"));
  }

  @Test
  void rateLimitsRepeatedFailedLogins() throws Exception {
    String body =
        """
        {
          "email": "missing@yuuka.local",
          "password": "password123"
        }
        """;

    for (int attempt = 0; attempt < 5; attempt++) {
      mockMvc
          .perform(
              post("/api/v1/auth/login")
                  .with(
                      request -> {
                        request.setRemoteAddr("192.0.2.20");
                        return request;
                      })
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isUnauthorized());
    }

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .with(
                    request -> {
                      request.setRemoteAddr("192.0.2.20");
                      return request;
                    })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void signsInAnExistingAccountAndLoadsOwnerPreferences() throws Exception {
    String email = "login-success@yuuka.local";
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"Password12345","displayName":"Owner"}
                    """
                        .formatted(email)))
        .andExpect(status().isCreated());

    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"%s","password":"Password12345","totpCode":""}
                        """
                            .formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refreshToken").isString())
            .andReturn();
    String accessToken =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(login.getResponse().getContentAsString())
            .path("accessToken")
            .asText();

    mockMvc
        .perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.currencyCode").value("USD"));
  }
}
