package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.mcp.support.AbstractMcpTest;
import com.yuuka.backend.mcp.support.SyntheticMcpTokens;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Proves the security model that makes disabling CSRF on {@code /mcp} safe: only a bearer token in
 * the Authorization header authenticates; cookies, sessions, query/form tokens and Basic do not;
 * disallowed Origins are rejected; and no fallback authentication filter exists in any chain. REST
 * rejecting MCP tokens (and the reverse) is covered by {@link McpAuthTests}.
 */
class McpBrowserCredentialTests extends AbstractMcpTest {
  @Autowired private FilterChainProxy filterChainProxy;

  private static final String METADATA_PATH = "/.well-known/oauth-protected-resource/mcp";

  private MockHttpServletRequestBuilder list() throws Exception {
    return post("/mcp")
        .header("MCP-Protocol-Version", MODERN)
        .header("Mcp-Method", "tools/list")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(modernBody(1, "tools/list", null)));
  }

  private void assertDeniedWithNoData(MvcResult result, int status) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(status);
    assertThat(result.getResponse().getContentAsString()).doesNotContain("tools", "result");
  }

  @Test
  void cookieOnlyRequestIsUnauthorized() throws Exception {
    ensureOwner(1);
    for (String name : new String[] {"JSESSIONID", "SESSION", "remember-me"}) {
      assertDeniedWithNoData(
          mockMvc.perform(list().cookie(new Cookie(name, "synthetic-value"))).andReturn(), 401);
    }
  }

  @Test
  void preAuthenticatedSessionIsIgnored() throws Exception {
    ensureOwner(1);
    SecurityContextImpl context = new SecurityContextImpl();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            "subject-1", "n/a", AuthorityUtils.createAuthorityList("SCOPE_yuuka:budget:read")));
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    assertDeniedWithNoData(mockMvc.perform(list().session(session)).andReturn(), 401);
  }

  @Test
  void queryTokenIsNotAccepted() throws Exception {
    ensureOwner(1);
    assertDeniedWithNoData(
        mockMvc.perform(list().queryParam("access_token", mcpToken(1))).andReturn(), 401);
  }

  @Test
  void formEncodedTokenIsNotAccepted() throws Exception {
    ensureOwner(1);
    MvcResult result =
        mockMvc
            .perform(
                post("/mcp")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("access_token", mcpToken(1))
                    .content("access_token=" + mcpToken(1)))
            .andReturn();
    assertDeniedWithNoData(result, 401);
  }

  @Test
  void httpBasicIsNotAccepted() throws Exception {
    ensureOwner(1);
    String basic =
        Base64.getEncoder()
            .encodeToString("mcp-owner-1@yuuka.test.invalid:Password12345".getBytes());
    assertDeniedWithNoData(
        mockMvc.perform(list().header("Authorization", "Basic " + basic)).andReturn(), 401);
  }

  @Test
  void bearerCases() throws Exception {
    ensureOwner(1);
    mockMvc
        .perform(list().header("Authorization", "Bearer " + mcpToken(1)))
        .andExpect(status().isOk());

    String noScope = SyntheticMcpTokens.spec("subject-1").scope(null).build();
    JsonNode listed =
        objectMapper.readTree(
            mockMvc
                .perform(list().header("Authorization", "Bearer " + noScope))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(listed.at("/result/tools")).isEmpty();
    ObjectNode params = objectMapper.createObjectNode();
    params.put("name", "yuuka_get_budget_context");
    params.putObject("arguments");
    mockMvc
        .perform(
            modernRequest(
                noScope,
                "tools/call",
                "yuuka_get_budget_context",
                modernBody(2, "tools/call", params)))
        .andExpect(status().isForbidden());

    String expired =
        SyntheticMcpTokens.spec("subject-1").expiresAt(Instant.now().minusSeconds(3600)).build();
    for (String token : new String[] {"garbage", expired}) {
      assertDeniedWithNoData(
          mockMvc.perform(list().header("Authorization", "Bearer " + token)).andReturn(), 401);
    }
  }

  @Test
  void originIsCheckedBeforeAuthentication() throws Exception {
    ensureOwner(1);
    String bad = "https://evil.test.invalid";
    assertDeniedWithNoData(mockMvc.perform(list().header("Origin", bad)).andReturn(), 403);
    assertDeniedWithNoData(
        mockMvc
            .perform(list().header("Origin", bad).header("Authorization", "Bearer " + mcpToken(1)))
            .andReturn(),
        403);
    mockMvc
        .perform(
            list()
                .header("Origin", "https://allowed.test.invalid")
                .header("Authorization", "Bearer " + mcpToken(1)))
        .andExpect(status().isOk());
  }

  @Test
  void corsPreflightIsNotHandledOnMcp() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/mcp")
                    .header("Origin", "https://allowed.test.invalid")
                    .header("Access-Control-Request-Method", "POST"))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isIn(401, 403, 405);
    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
  }

  // ---- filter-chain structure ---------------------------------------------------------------

  private SecurityFilterChain firstChain(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return filterChainProxy.getFilterChains().stream()
        .filter(chain -> chain.matches(request))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> filterNames(SecurityFilterChain chain) {
    List<String> names = new ArrayList<>();
    for (Filter filter : chain.getFilters()) {
      names.add(filter.getClass().getSimpleName());
    }
    return names;
  }

  @Test
  void mcpPathsMatchTheMcpChainFirstAndMetadataMatchesTheMetadataChain() {
    SecurityFilterChain mcp = firstChain("POST", "/mcp");
    assertThat(filterNames(mcp)).contains("McpOriginFilter", "BearerTokenAuthenticationFilter");
    for (String path : new String[] {"/mcp/", "/mcp/x"}) {
      assertThat(firstChain("POST", path)).as(path).isSameAs(mcp);
    }
    SecurityFilterChain metadata = firstChain("GET", METADATA_PATH);
    assertThat(metadata).isNotSameAs(mcp);
    assertThat(filterNames(metadata)).doesNotContain("BearerTokenAuthenticationFilter");
    SecurityFilterChain rest = firstChain("POST", "/api/v1/paychecks");
    assertThat(rest).isNotSameAs(mcp).isNotSameAs(metadata);
    // Non-GET on the metadata path falls to the REST chain (401 there).
    assertThat(firstChain("POST", METADATA_PATH)).isSameAs(rest);
  }

  @Test
  void noChainContainsFallbackAuthenticationFilters() {
    List<String> forbidden =
        List.of(
            "BasicAuthenticationFilter",
            "UsernamePasswordAuthenticationFilter",
            "RememberMeAuthenticationFilter",
            "DigestAuthenticationFilter",
            "DefaultLoginPageGeneratingFilter",
            "DefaultLogoutPageGeneratingFilter",
            "ConcurrentSessionFilter",
            "OAuth2LoginAuthenticationFilter",
            "OAuth2AuthorizationRequestRedirectFilter");
    for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
      assertThat(filterNames(chain)).doesNotContainAnyElementsOf(forbidden);
    }
  }

  @Test
  void mcpChainDoesNotLoadSecurityContextFromTheHttpSession() {
    for (SecurityFilterChain chain :
        List.of(firstChain("POST", "/mcp"), firstChain("GET", METADATA_PATH))) {
      Object repository =
          chain.getFilters().stream()
              .filter(SecurityContextHolderFilter.class::isInstance)
              .map(f -> ReflectionTestUtils.getField(f, "securityContextRepository"))
              .findFirst()
              .orElseThrow();
      assertThat(repository).isNotInstanceOf(HttpSessionSecurityContextRepository.class);
      assertThat(repository.getClass().getSimpleName())
          .isIn("RequestAttributeSecurityContextRepository");
    }
  }

  // ---- unusual paths ------------------------------------------------------------------------

  /**
   * StrictHttpFirewall rejects semicolons, dot segments and encoded slashes (400) before any chain
   * runs; a different case is simply not the MCP path and falls to the REST chain (401). Neither
   * ever returns data, even with a valid MCP token.
   */
  @Test
  void unusualPathsNeverReturnData() throws Exception {
    ensureOwner(1);
    String token = "Bearer " + mcpToken(1);
    String body = objectMapper.writeValueAsString(modernBody(1, "tools/list", null));
    String[] paths = {"/mcp;jsessionid=x", "/MCP", "/mcp/../mcp", "/mcp%2F"};
    int[] expected = {400, 401, 400, 400};
    for (int i = 0; i < paths.length; i++) {
      MvcResult result =
          mockMvc
              .perform(
                  post(java.net.URI.create("http://localhost" + paths[i]))
                      .header("Authorization", token)
                      .header("MCP-Protocol-Version", MODERN)
                      .header("Mcp-Method", "tools/list")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(body))
              .andReturn();
      assertThat(result.getResponse().getStatus()).as(paths[i]).isEqualTo(expected[i]);
      assertThat(result.getResponse().getContentAsString()).as(paths[i]).doesNotContain("tools");
    }
    mockMvc.perform(get("/MCP")).andExpect(status().isUnauthorized());
  }
}
