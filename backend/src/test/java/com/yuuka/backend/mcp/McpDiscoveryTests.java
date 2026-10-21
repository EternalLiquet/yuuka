package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.mcp.support.AbstractMcpTest;
import com.yuuka.backend.mcp.support.SyntheticMcpTokens;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** ADR 4.3 / 8.1: discovery bootstrap sequence and the metadata-only chain, enabled. */
class McpDiscoveryTests extends AbstractMcpTest {
  private static final String METADATA_PATH = "/.well-known/oauth-protected-resource/mcp";
  private static final String METADATA_URL = "https://yuuka.test.invalid" + METADATA_PATH;

  private MockHttpServletRequestBuilder anonymousMcp(String method) throws Exception {
    ObjectNode params = objectMapper.createObjectNode();
    if (method.equals("tools/call")) {
      params.put("name", "yuuka_get_budget_context");
      params.putObject("arguments");
    }
    return post("/mcp")
        .header("MCP-Protocol-Version", MODERN)
        .header("Mcp-Method", method)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(modernBody(1, method, params)));
  }

  @Test
  void discoveryBootstrapSequence() throws Exception {
    ensureOwner(1);
    // 1. No token: exact challenge.
    MvcResult challenge =
        mockMvc
            .perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(challenge.getResponse().getHeader("WWW-Authenticate"))
        .isEqualTo(
            "Bearer resource_metadata=\"" + METADATA_URL + "\", scope=\"yuuka:budget:read\"");

    // 2. Metadata is anonymous, and a garbage Authorization header is ignored on this route.
    for (String authorization : new String[] {null, "Bearer garbage", "Basic Zm9vOmJhcg=="}) {
      MockHttpServletRequestBuilder request = get(METADATA_PATH);
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
      JsonNode metadata = objectMapper.readTree(result.getResponse().getContentAsString());
      List<String> fields = new ArrayList<>();
      Iterator<String> names = metadata.fieldNames();
      names.forEachRemaining(fields::add);
      assertThat(fields)
          .containsExactlyInAnyOrder(
              "resource", "authorization_servers", "scopes_supported", "bearer_methods_supported");
      assertThat(metadata.get("resource").asText()).isEqualTo(SyntheticMcpTokens.RESOURCE_URI);
      assertThat(metadata.at("/scopes_supported/0").asText()).isEqualTo("yuuka:budget:read");
      assertThat(metadata.get("scopes_supported")).hasSize(1);
      assertThat(metadata.at("/bearer_methods_supported/0").asText()).isEqualTo("header");
      assertThat(metadata.get("bearer_methods_supported")).hasSize(1);
      // 3. The authorization server is the configured issuer; Yuuka serves no AS metadata.
      assertThat(metadata.get("authorization_servers")).hasSize(1);
      assertThat(metadata.at("/authorization_servers/0").asText())
          .isEqualTo(SyntheticMcpTokens.ISSUER);
      assertThat(result.getResponse().getContentAsString())
          .doesNotContain("owner", "subject", "email", "00000000");
    }

    // 4. A valid token works on /mcp.
    mockMvc
        .perform(modernRequest(mcpToken(1), "tools/list", null, modernBody(1, "tools/list", null)))
        .andExpect(status().isOk());
  }

  @Test
  void invalidTokenChallengeAddsErrorAndKeepsMetadataAndScope() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/mcp")
                    .header("Authorization", "Bearer garbage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(result.getResponse().getHeader("WWW-Authenticate"))
        .isEqualTo(
            "Bearer error=\"invalid_token\", resource_metadata=\""
                + METADATA_URL
                + "\", scope=\"yuuka:budget:read\"");
  }

  @Test
  void metadataChainMatchesOnlyGetOnTheExactPath() throws Exception {
    for (var request :
        List.of(
            post(METADATA_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"),
            put(METADATA_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"),
            delete(METADATA_PATH))) {
      mockMvc.perform(request).andExpect(status().isUnauthorized());
    }
    for (String path :
        new String[] {
          "/.well-known/oauth-protected-resource",
          "/.well-known/oauth-protected-resource/mcp/extra",
          "/.well-known/oauth-protected-resource/other",
          "/.well-known/other"
        }) {
      MvcResult result = mockMvc.perform(get(path)).andReturn();
      assertThat(result.getResponse().getStatus()).as(path).isEqualTo(401);
      assertThat(result.getResponse().getContentAsString()).as(path).doesNotContain("resource");
    }
  }

  @Test
  void metadataAccessIsNotAnonymousToolAccess() throws Exception {
    for (String method : new String[] {"server/discover", "tools/list", "tools/call"}) {
      MvcResult result =
          mockMvc.perform(anonymousMcp(method)).andExpect(status().isUnauthorized()).andReturn();
      assertThat(result.getResponse().getContentAsString()).doesNotContain("tools", "result");
    }
  }

  @Test
  void restRoutesStayProtectedAndMcpTokenCannotAuthenticateThem() throws Exception {
    mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/me").header("Authorization", "Bearer " + mcpToken(1)))
        .andExpect(status().isUnauthorized());
  }
}
