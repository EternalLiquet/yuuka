package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuuka.backend.common.security.JwtTokenService;
import com.yuuka.backend.mcp.api.McpEndpointController;
import com.yuuka.backend.mcp.security.McpTokenDecoder;
import com.yuuka.backend.mcp.tools.McpTool;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Default configuration: MCP disabled, no decoder, endpoint denies everything. */
@AutoConfigureMockMvc
class McpDisabledByDefaultTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext context;
  @Autowired private JwtTokenService tokens;

  @Test
  void noMcpBeansAreRegisteredAndNoTokenDecoderShips() {
    assertThat(context.getBeansOfType(McpEndpointController.class)).isEmpty();
    assertThat(context.getBeansOfType(McpTool.class)).isEmpty();
    assertThat(context.getBeansOfType(McpTokenDecoder.class)).isEmpty();
  }

  @Test
  void endpointFailsClosedForEveryRequestShape() throws Exception {
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    for (String authorization : new String[] {null, "Bearer garbage", "Bearer a.b.c"}) {
      var request = post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body);
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      MvcResult result = mockMvc.perform(request).andReturn();
      assertThat(result.getResponse().getStatus()).isIn(401, 403, 404);
      assertThat(result.getResponse().getContentAsString()).doesNotContain("tools", "result");
    }
    mockMvc.perform(get("/mcp")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/mcp/anything")).andExpect(status().isUnauthorized());
  }

  @Test
  void ordinaryOwnerTokenCannotReachDisabledEndpoint() throws Exception {
    // Register a normal owner so that a real HS256 REST token exists.
    var register =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"mcp-disabled@yuuka.test.invalid\",\"password\":\"Password12345\",\"displayName\":\"T\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    String token =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(register.getResponse().getContentAsString())
            .path("accessToken")
            .asText();
    MvcResult result =
        mockMvc
            .perform(
                post("/mcp")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isIn(401, 403, 404);
    assertThat(Map.of("t", tokens).size()).isEqualTo(1);
  }

  @Test
  void metadataPathIsNotServedAndFallsToTheRestChainWhileDisabled() throws Exception {
    assertThat(
            context.getBeansOfType(
                com.yuuka.backend.mcp.api.McpProtectedResourceMetadataController.class))
        .isEmpty();
    for (String authorization : new String[] {null, "Bearer garbage"}) {
      var request = get("/.well-known/oauth-protected-resource/mcp");
      if (authorization != null) {
        request.header("Authorization", authorization);
      }
      MvcResult result = mockMvc.perform(request).andExpect(status().isUnauthorized()).andReturn();
      assertThat(result.getResponse().getContentAsString()).doesNotContain("resource");
    }
  }

  @Test
  void disallowedOriginIsForbiddenEvenWhenDisabled() throws Exception {
    mockMvc
        .perform(
            post("/mcp")
                .header("Origin", "https://evil.test.invalid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
        .andExpect(status().isForbidden());
  }
}
