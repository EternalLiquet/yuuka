package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.mcp.protocol.McpProtocol;
import com.yuuka.backend.mcp.support.AbstractMcpTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class McpProtocolTests extends AbstractMcpTest {
  private static final List<String> TOOL_NAMES =
      List.of(
          "yuuka_get_budget_context",
          "yuuka_get_paycheck",
          "yuuka_get_recurring_coverage",
          "yuuka_list_paychecks",
          "yuuka_search_entries");

  private JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    assertThat(result.getResponse().getContentType()).startsWith("application/json");
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private MockHttpServletRequestBuilder legacy(String version, String json) {
    MockHttpServletRequestBuilder builder =
        post("/mcp")
            .header("Authorization", "Bearer " + mcpToken(1))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json);
    if (version != null) {
      builder.header("MCP-Protocol-Version", version);
    }
    return builder;
  }

  @Test
  void supportedVersionsAreOneConstant() {
    assertThat(McpProtocol.SUPPORTED_PROTOCOL_VERSIONS)
        .containsExactly("2026-07-28", "2025-11-25", "2025-06-18");
  }

  @Test
  void serverDiscoverUsesStatelessRevision() throws Exception {
    ensureOwner(1);
    JsonNode body =
        send(
            modernRequest(
                mcpToken(1), "server/discover", null, modernBody(1, "server/discover", null)),
            200);
    JsonNode result = body.path("result");
    assertThat(result.path("resultType").asText()).isEqualTo("complete");
    assertThat(result.path("ttlMs").asInt()).isZero();
    assertThat(result.path("cacheScope").asText()).isEqualTo("private");
    assertThat(result.path("supportedVersions"))
        .extracting(JsonNode::asText)
        .containsExactly("2026-07-28", "2025-11-25", "2025-06-18");
    assertThat(result.at("/capabilities/tools").isObject()).isTrue();
    assertThat(result.at("/_meta/io.modelcontextprotocol~1serverInfo/name").asText())
        .isEqualTo("yuuka");
    assertThat(result.at("/_meta/io.modelcontextprotocol~1serverInfo/version").asText())
        .isNotBlank();
  }

  @Test
  void toolsListHasExactlyFiveReadOnlyStrictTools() throws Exception {
    ensureOwner(1);
    JsonNode body =
        send(
            modernRequest(mcpToken(1), "tools/list", null, modernBody(1, "tools/list", null)), 200);
    JsonNode tools = body.at("/result/tools");
    List<String> names = new ArrayList<>();
    tools.forEach(tool -> names.add(tool.path("name").asText()));
    assertThat(names).containsExactlyElementsOf(TOOL_NAMES);
    assertThat(body.at("/result/ttlMs").asInt()).isZero();
    assertThat(body.at("/result/cacheScope").asText()).isEqualTo("private");
    for (JsonNode tool : tools) {
      assertThat(tool.path("title").asText()).isNotBlank();
      assertThat(tool.path("description").asText()).isNotBlank();
      assertThat(tool.at("/inputSchema/type").asText()).isEqualTo("object");
      assertThat(tool.at("/inputSchema/additionalProperties").asBoolean(true)).isFalse();
      assertThat(tool.at("/inputSchema/$schema").asText())
          .isEqualTo("https://json-schema.org/draft/2020-12/schema");
      assertThat(tool.at("/outputSchema/additionalProperties").asBoolean(true)).isFalse();
      assertThat(tool.at("/annotations/readOnlyHint").asBoolean()).isTrue();
      assertThat(tool.at("/annotations/destructiveHint").asBoolean(true)).isFalse();
      assertThat(tool.at("/annotations/idempotentHint").asBoolean()).isTrue();
      assertThat(tool.at("/annotations/openWorldHint").asBoolean(true)).isFalse();
    }
  }

  @Test
  void headerMismatchAndMissingHeadersAreRejectedWithMinus32020() throws Exception {
    ensureOwner(1);
    String token = mcpToken(1);
    ObjectNode params = objectMapper.createObjectNode();
    params.put("name", "yuuka_get_budget_context");
    params.putObject("arguments");
    ObjectNode call = modernBody(5, "tools/call", params);

    // Mcp-Method does not match the body method.
    JsonNode wrongMethod =
        send(modernRequest(token, "tools/list", "yuuka_get_budget_context", call), 400);
    assertThat(wrongMethod.at("/error/code").asInt()).isEqualTo(-32020);
    // Mcp-Name does not match params.name.
    JsonNode wrongName = send(modernRequest(token, "tools/call", "yuuka_get_paycheck", call), 400);
    assertThat(wrongName.at("/error/code").asInt()).isEqualTo(-32020);
    // Mcp-Name missing for tools/call.
    JsonNode missingName = send(modernRequest(token, "tools/call", null, call), 400);
    assertThat(missingName.at("/error/code").asInt()).isEqualTo(-32020);
    // _meta version differs from the header.
    ObjectNode other = modernBody(6, "tools/list", null);
    ((ObjectNode) other.path("params").path("_meta"))
        .put("io.modelcontextprotocol/protocolVersion", "2025-11-25");
    JsonNode metaMismatch = send(modernRequest(token, "tools/list", null, other), 400);
    assertThat(metaMismatch.at("/error/code").asInt()).isEqualTo(-32020);
    // server/discover without the protocol header cannot be a legacy request.
    JsonNode noHeader =
        send(
            post("/mcp")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modernBody(8, "server/discover", null))),
            400);
    assertThat(noHeader.at("/error/code").asInt()).isEqualTo(-32020);
    // Base64 sentinel form of Mcp-Name is accepted.
    String encoded =
        "=?base64?"
            + java.util.Base64.getEncoder().encodeToString("yuuka_get_budget_context".getBytes())
            + "?=";
    JsonNode ok = send(modernRequest(token, "tools/call", encoded, call), 200);
    assertThat(ok.at("/result/isError").asBoolean(true)).isFalse();
  }

  @Test
  void unsupportedVersionReturns400WithSupportedList() throws Exception {
    ensureOwner(1);
    ObjectNode body = modernBody(1, "tools/list", null);
    ((ObjectNode) body.path("params").path("_meta"))
        .put("io.modelcontextprotocol/protocolVersion", "2099-01-01");
    JsonNode error =
        send(
            post("/mcp")
                .header("Authorization", "Bearer " + mcpToken(1))
                .header("MCP-Protocol-Version", "2099-01-01")
                .header("Mcp-Method", "tools/list")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
            400);
    assertThat(error.at("/error/code").asInt()).isEqualTo(-32022);
    assertThat(error.at("/error/data/requested").asText()).isEqualTo("2099-01-01");
    assertThat(error.at("/error/data/supported"))
        .extracting(JsonNode::asText)
        .containsExactly("2026-07-28", "2025-11-25", "2025-06-18");
  }

  @Test
  void unknownMethodReturns404WithMinus32601() throws Exception {
    ensureOwner(1);
    JsonNode error =
        send(
            modernRequest(
                mcpToken(1), "resources/list", null, modernBody(1, "resources/list", null)),
            404);
    assertThat(error.at("/error/code").asInt()).isEqualTo(-32601);
    JsonNode legacyUnknown =
        send(
            legacy("2025-11-25", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\"}"),
            404);
    assertThat(legacyUnknown.at("/error/code").asInt()).isEqualTo(-32601);
  }

  @Test
  void legacyInitializeToolsListAndPingWork() throws Exception {
    ensureOwner(1);
    JsonNode init =
        send(
            legacy(
                null,
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18","capabilities":{},
                  "clientInfo":{"name":"synthetic","version":"0"}}}
                """),
            200);
    assertThat(init.at("/result/protocolVersion").asText()).isEqualTo("2025-06-18");
    assertThat(init.at("/result/capabilities/tools").isObject()).isTrue();
    assertThat(init.at("/result/serverInfo/name").asText()).isEqualTo("yuuka");

    JsonNode newer =
        send(
            legacy(
                null,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2099-01-01\"}}"),
            200);
    assertThat(newer.at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");

    MvcResult initialized =
        mockMvc
            .perform(
                legacy(
                    "2025-11-25", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
            .andExpect(status().isAccepted())
            .andReturn();
    assertThat(initialized.getResponse().getContentAsString()).isEmpty();
    assertThat(initialized.getResponse().getHeader("Mcp-Session-Id")).isNull();

    JsonNode ping =
        send(legacy("2025-11-25", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}"), 200);
    assertThat(ping.path("result").isObject()).isTrue();

    JsonNode tools =
        send(legacy("2025-06-18", "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}"), 200);
    assertThat(tools.at("/result/tools")).hasSize(5);

    JsonNode call =
        send(
            legacy(
                "2025-11-25",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"yuuka_get_budget_context\",\"arguments\":{}}}"),
            200);
    assertThat(call.at("/result/isError").asBoolean(true)).isFalse();
    assertThat(call.at("/result/structuredContent/tool").asText())
        .isEqualTo("yuuka_get_budget_context");
    assertThat(call.at("/result/content/0/type").asText()).isEqualTo("text");
    assertThat(objectMapper.readTree(call.at("/result/content/0/text").asText()))
        .isEqualTo(call.at("/result/structuredContent"));
  }

  @Test
  void getAndDeleteAreNotAllowedAndNoSessionIsMinted() throws Exception {
    ensureOwner(1);
    String bearer = "Bearer " + mcpToken(1);
    MvcResult getResult =
        mockMvc
            .perform(get("/mcp").header("Authorization", bearer))
            .andExpect(status().isMethodNotAllowed())
            .andReturn();
    assertThat(getResult.getResponse().getHeader("Allow")).isEqualTo("POST");
    mockMvc
        .perform(delete("/mcp").header("Authorization", bearer))
        .andExpect(status().isMethodNotAllowed());
    MvcResult post =
        mockMvc
            .perform(
                modernRequest(mcpToken(1), "tools/list", null, modernBody(1, "tools/list", null))
                    .header("Mcp-Session-Id", "ignored"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(post.getResponse().getHeader("Mcp-Session-Id")).isNull();
  }

  @Test
  void malformedJsonRpcIsRejected() throws Exception {
    ensureOwner(1);
    JsonNode parse = send(legacy("2025-11-25", "{not json"), 400);
    assertThat(parse.at("/error/code").asInt()).isEqualTo(-32700);
    JsonNode batch =
        send(legacy("2025-11-25", "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"), 400);
    assertThat(batch.at("/error/code").asInt()).isEqualTo(-32600);
    JsonNode noMethod = send(legacy("2025-11-25", "{\"jsonrpc\":\"2.0\",\"id\":1}"), 400);
    assertThat(noMethod.at("/error/code").asInt()).isEqualTo(-32600);
  }

  @Test
  void unknownToolIsAProtocolErrorAndNoGenericToolsExist() throws Exception {
    ensureOwner(1);
    for (String name : List.of("run_sql", "export", "http_request", "yuuka_create_paycheck")) {
      JsonNode body = callTool(1, name, "{}");
      assertThat(body.at("/error/code").asInt()).isEqualTo(-32602);
    }
  }

  private MockHttpServletRequestBuilder raw(String token, String json) {
    return post("/mcp")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json);
  }

  @Test
  void modernMcpNameIsRequiredOnlyForToolsCall() throws Exception {
    ensureOwner(1);
    String token = mcpToken(1);
    send(
        modernRequest(token, "server/discover", null, modernBody(1, "server/discover", null)), 200);
    send(modernRequest(token, "tools/list", null, modernBody(2, "tools/list", null)), 200);
    ObjectNode params = objectMapper.createObjectNode();
    params.put("name", "yuuka_get_budget_context");
    params.putObject("arguments");
    ObjectNode call = modernBody(3, "tools/call", params);
    assertThat(send(modernRequest(token, "tools/call", null, call), 400).at("/error/code").asInt())
        .isEqualTo(-32020);
    assertThat(
            send(modernRequest(token, "tools/call", "yuuka_get_paycheck", call), 400)
                .at("/error/code")
                .asInt())
        .isEqualTo(-32020);
    // Mcp-Method is required on every modern request.
    MockHttpServletRequestBuilder noMethod =
        post("/mcp")
            .header("Authorization", "Bearer " + token)
            .header("MCP-Protocol-Version", MODERN)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(modernBody(4, "tools/list", null)));
    assertThat(send(noMethod, 400).at("/error/code").asInt()).isEqualTo(-32020);
  }

  @Test
  void legacyHeaderRulesFollowTheCorrectedContract() throws Exception {
    ensureOwner(1);
    String token = mcpToken(1);
    // initialize needs no header.
    send(
        raw(
            token,
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}"),
        200);
    // Later requests need the header but not Mcp-Method or Mcp-Name.
    send(
        raw(token, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
            .header("MCP-Protocol-Version", "2025-11-25"),
        200);
    send(
        raw(
                token,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"yuuka_get_budget_context\",\"arguments\":{}}}")
            .header("MCP-Protocol-Version", "2025-06-18"),
        200);
    // Without the header: 400 naming the supported versions.
    JsonNode missing =
        send(raw(token, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}"), 400);
    assertThat(missing.at("/error/data/supported"))
        .extracting(JsonNode::asText)
        .containsExactly("2026-07-28", "2025-11-25", "2025-06-18");
    JsonNode ping = send(raw(token, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}"), 400);
    assertThat(ping.at("/error/data/supported")).hasSize(3);
    mockMvc
        .perform(raw(token, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
        .andExpect(status().isBadRequest());
  }
}
