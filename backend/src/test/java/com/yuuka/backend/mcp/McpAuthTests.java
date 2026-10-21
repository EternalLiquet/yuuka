package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.mcp.support.AbstractMcpTest;
import com.yuuka.backend.mcp.support.SyntheticMcpTokens;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class McpAuthTests extends AbstractMcpTest {
  private MvcResult list(String token, int expectedStatus) throws Exception {
    return mockMvc
        .perform(modernRequest(token, "tools/list", null, modernBody(1, "tools/list", null)))
        .andExpect(status().is(expectedStatus))
        .andReturn();
  }

  @Test
  void validTokenWithReadScopeListsTools() throws Exception {
    ensureOwner(1);
    MvcResult result = list(mcpToken(1), 200);
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.at("/result/tools")).hasSize(6);
  }

  @Test
  void missingTokenIsUnauthorizedWithBearerChallenge() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(result.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
  }

  @Test
  void rejectsWrongIssuerAudienceExpiredAndNotYetValidTokens() throws Exception {
    ensureOwner(1);
    list(SyntheticMcpTokens.spec("subject-1").issuer("https://other.test.invalid").build(), 401);
    list(
        SyntheticMcpTokens.spec("subject-1").audience("https://other.test.invalid/mcp").build(),
        401);
    list(
        SyntheticMcpTokens.spec("subject-1").expiresAt(Instant.now().minusSeconds(3600)).build(),
        401);
    list(
        SyntheticMcpTokens.spec("subject-1").notBefore(Instant.now().plusSeconds(3600)).build(),
        401);
    list("not-a-jwt", 401);
  }

  @Test
  void ordinaryOwnerAccessTokenIsRejectedAtMcp() throws Exception {
    list(restToken(1), 401);
  }

  @Test
  void missingScopeListsNoToolsAndRefusesToolCalls() throws Exception {
    ensureOwner(1);
    String noScope = SyntheticMcpTokens.spec("subject-1").scope(null).build();
    String otherScope = SyntheticMcpTokens.spec("subject-1").scope("profile").build();
    for (String token : new String[] {noScope, otherScope}) {
      JsonNode listed = objectMapper.readTree(list(token, 200).getResponse().getContentAsString());
      assertThat(listed.at("/result/tools")).isEmpty();
      assertThat(listed.at("/result/resultType").asText()).isEqualTo("complete");

      ObjectNode params = objectMapper.createObjectNode();
      params.put("name", "yuuka_get_budget_context");
      params.putObject("arguments");
      MvcResult denied =
          mockMvc
              .perform(
                  modernRequest(
                      token,
                      "tools/call",
                      "yuuka_get_budget_context",
                      modernBody(7, "tools/call", params)))
              .andExpect(status().isForbidden())
              .andReturn();
      assertThat(denied.getResponse().getHeader("WWW-Authenticate"))
          .isEqualTo("Bearer error=\"insufficient_scope\", scope=\"yuuka:budget:read\"");
      JsonNode error = objectMapper.readTree(denied.getResponse().getContentAsString());
      assertThat(error.at("/error/code").asInt()).isEqualTo(-32000);
      assertThat(error.at("/error/message").asText()).isEqualTo("Forbidden");
      assertThat(error.at("/id").asInt()).isEqualTo(7);
    }
  }

  @Test
  void unmappedAndUnknownSubjectsAreForbiddenWithoutDetail() throws Exception {
    for (String subject : new String[] {"stranger", "ghost"}) {
      MvcResult result =
          mockMvc
              .perform(
                  modernRequest(
                      SyntheticMcpTokens.spec(subject).build(),
                      "tools/list",
                      null,
                      modernBody(3, "tools/list", null)))
              .andExpect(status().isForbidden())
              .andReturn();
      JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
      assertThat(error.at("/error/code").asInt()).isEqualTo(-32000);
      assertThat(error.at("/error/message").asText()).isEqualTo("Forbidden");
      assertThat(error.at("/error/data").isMissingNode()).isTrue();
      assertThat(result.getResponse().getHeader("WWW-Authenticate")).isNull();
      assertThat(result.getResponse().getContentAsString()).doesNotContain("subject", "owner");
    }
  }

  @Test
  void mcpTokenCannotMutateOrReadOrdinaryApis() throws Exception {
    ensureOwner(1);
    String token = mcpToken(1);
    mockMvc
        .perform(
            post("/api/v1/paychecks")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"amountMinor\":1,\"incomeDate\":\"2026-01-01\"}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            patch("/api/v1/entries/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            delete("/api/v1/entries/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/paychecks/active").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void originIsValidatedBeforeAuthentication() throws Exception {
    ensureOwner(1);
    String bad = "https://evil.test.invalid";
    String body = objectMapper.writeValueAsString(modernBody(1, "tools/list", null));
    for (String authorization : new String[] {null, "Bearer garbage", "Bearer " + mcpToken(1)}) {
      var request =
          post("/mcp")
              .header("Origin", bad)
              .header("MCP-Protocol-Version", MODERN)
              .header("Mcp-Method", "tools/list")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body);
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      MvcResult result = mockMvc.perform(request).andExpect(status().isForbidden()).andReturn();
      JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
      assertThat(error.at("/error/code").asInt()).isEqualTo(-32000);
      assertThat(error.at("/error/message").asText()).isEqualTo("Forbidden");
      assertThat(error.get("id").isNull()).isTrue();
    }
    mockMvc
        .perform(
            modernRequest(mcpToken(1), "tools/list", null, modernBody(1, "tools/list", null))
                .header("Origin", "https://allowed.test.invalid"))
        .andExpect(status().isOk());
    mockMvc
        .perform(modernRequest(mcpToken(1), "tools/list", null, modernBody(1, "tools/list", null)))
        .andExpect(status().isOk());
  }
}
