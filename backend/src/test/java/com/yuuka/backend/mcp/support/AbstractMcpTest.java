package com.yuuka.backend.mcp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.security.JwtTokenService;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Enables the MCP surface with the synthetic decoder. Owners 1..8 are mapped from synthetic
 * subjects "subject-N" to fixed owner ids so each test class can use its own isolated owners.
 */
@AutoConfigureMockMvc
@Import(SyntheticMcpAuthConfig.class)
@TestPropertySource(
    properties = {
      "yuuka.mcp.enabled=true",
      "yuuka.mcp.resource-uri=" + SyntheticMcpTokens.RESOURCE_URI,
      "yuuka.mcp.allowed-origins=https://allowed.test.invalid",
      "yuuka.mcp.owner-mappings[0].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[0].subject=subject-1",
      "yuuka.mcp.owner-mappings[0].owner-id=00000000-0000-0000-0000-0000000000a1",
      "yuuka.mcp.owner-mappings[1].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[1].subject=subject-2",
      "yuuka.mcp.owner-mappings[1].owner-id=00000000-0000-0000-0000-0000000000a2",
      "yuuka.mcp.owner-mappings[2].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[2].subject=subject-3",
      "yuuka.mcp.owner-mappings[2].owner-id=00000000-0000-0000-0000-0000000000a3",
      "yuuka.mcp.owner-mappings[3].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[3].subject=subject-4",
      "yuuka.mcp.owner-mappings[3].owner-id=00000000-0000-0000-0000-0000000000a4",
      "yuuka.mcp.owner-mappings[4].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[4].subject=subject-5",
      "yuuka.mcp.owner-mappings[4].owner-id=00000000-0000-0000-0000-0000000000a5",
      "yuuka.mcp.owner-mappings[5].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[5].subject=subject-6",
      "yuuka.mcp.owner-mappings[5].owner-id=00000000-0000-0000-0000-0000000000a6",
      "yuuka.mcp.owner-mappings[6].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[6].subject=subject-7",
      "yuuka.mcp.owner-mappings[6].owner-id=00000000-0000-0000-0000-0000000000a7",
      "yuuka.mcp.owner-mappings[7].issuer=" + SyntheticMcpTokens.ISSUER,
      "yuuka.mcp.owner-mappings[7].subject=ghost",
      "yuuka.mcp.owner-mappings[7].owner-id=00000000-0000-0000-0000-0000000000ff",
    })
public abstract class AbstractMcpTest extends AbstractIntegrationTest {
  protected static final String MODERN = "2026-07-28";

  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected JpaUserAccountRepository users;
  @Autowired protected JwtTokenService restTokens;

  protected static UUID ownerId(int n) {
    return UUID.fromString("00000000-0000-0000-0000-0000000000a" + n);
  }

  /** Inserts the synthetic owner row if absent and returns its id. */
  protected UUID ensureOwner(int n) {
    UUID id = ownerId(n);
    jdbc.update(
        """
        insert into user_accounts (id, email, password_hash, display_name, role, created_at, updated_at)
        values (?, ?, 'not-a-real-hash', 'Synthetic', 'USER', now(), now())
        on conflict (id) do nothing
        """,
        id,
        "mcp-owner-" + n + "@yuuka.test.invalid");
    return id;
  }

  /** Ordinary HS256 REST access token for the synthetic owner (used only to create fixtures). */
  protected String restToken(int n) {
    ensureOwner(n);
    return restTokens.createAccessToken(users.findById(ownerId(n)).orElseThrow()).value();
  }

  protected String mcpToken(int n) {
    return SyntheticMcpTokens.spec("subject-" + n).build();
  }

  // ---- REST fixtures -------------------------------------------------------------------------

  protected JsonNode rest(MockHttpServletRequestBuilder request, int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andReturn();
    String content = result.getResponse().getContentAsString();
    if (result.getResponse().getStatus() != expectedStatus) {
      throw new AssertionError(
          "Expected HTTP "
              + expectedStatus
              + " but was "
              + result.getResponse().getStatus()
              + ": "
              + content);
    }
    return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
  }

  protected JsonNode createPaycheck(int owner, String name, long amountMinor, String incomeDate)
      throws Exception {
    return rest(
        post("/api/v1/paychecks")
            .header("Authorization", "Bearer " + restToken(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    Map.of("name", name, "amountMinor", amountMinor, "incomeDate", incomeDate))),
        201);
  }

  protected JsonNode addEntry(
      int owner, String paycheckId, String type, String name, long amountMinor) throws Exception {
    return rest(
        post("/api/v1/paychecks/{id}/entries", paycheckId)
            .header("Authorization", "Bearer " + restToken(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    Map.of("entryType", type, "name", name, "amountMinor", amountMinor))),
        201);
  }

  // ---- MCP calls -----------------------------------------------------------------------------

  protected ObjectNode modernBody(int id, String method, ObjectNode params) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", id);
    body.put("method", method);
    ObjectNode p = params == null ? objectMapper.createObjectNode() : params;
    p.putObject("_meta").put("io.modelcontextprotocol/protocolVersion", MODERN);
    body.set("params", p);
    return body;
  }

  protected MockHttpServletRequestBuilder modernRequest(
      String token, String method, String name, ObjectNode body) throws Exception {
    MockHttpServletRequestBuilder builder =
        post("/mcp")
            .header("Authorization", "Bearer " + token)
            .header("MCP-Protocol-Version", MODERN)
            .header("Mcp-Method", method)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body));
    if (name != null) {
      builder.header("Mcp-Name", name);
    }
    return builder;
  }

  /** Calls a tool as the given synthetic subject using the stateless protocol revision. */
  protected JsonNode callTool(int owner, String tool, String argumentsJson) throws Exception {
    return callToolWithToken(mcpToken(owner), tool, argumentsJson);
  }

  protected JsonNode callToolWithToken(String token, String tool, String argumentsJson)
      throws Exception {
    ObjectNode params = objectMapper.createObjectNode();
    params.put("name", tool);
    params.set("arguments", objectMapper.readTree(argumentsJson));
    MvcResult result =
        mockMvc
            .perform(modernRequest(token, "tools/call", tool, modernBody(1, "tools/call", params)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  /** Returns the structuredContent envelope of a tool call. */
  protected JsonNode envelope(int owner, String tool, String argumentsJson) throws Exception {
    return callTool(owner, tool, argumentsJson).path("result").path("structuredContent");
  }
}
