package com.yuuka.backend.mcp.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.protocol.McpCall;
import com.yuuka.backend.mcp.protocol.McpHttpResult;
import com.yuuka.backend.mcp.protocol.McpProtocolHandler;
import com.yuuka.backend.mcp.security.McpScopes;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Streamable HTTP endpoint: POST only, JSON responses only, no sessions, no SSE. */
@Hidden
@RestController
@ConditionalOnMcpEnabled
@RequestMapping("/mcp")
public class McpEndpointController {
  private final McpProtocolHandler handler;

  public McpEndpointController(McpProtocolHandler handler) {
    this.handler = handler;
  }

  @PostMapping
  public ResponseEntity<String> post(
      @RequestBody(required = false) String body,
      @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
      @RequestHeader(value = "Mcp-Method", required = false) String method,
      @RequestHeader(value = "Mcp-Name", required = false) String name,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    boolean scope =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(McpScopes.BUDGET_READ_AUTHORITY::equals);
    return render(handler.handle(new McpCall(body, protocolVersion, method, name, jwt, scope)));
  }

  @RequestMapping(
      method = {RequestMethod.GET, RequestMethod.DELETE, RequestMethod.PUT, RequestMethod.PATCH})
  public ResponseEntity<Void> unsupported() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .header(HttpHeaders.ALLOW, "POST")
        .build();
  }

  private ResponseEntity<String> render(McpHttpResult result) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status());
    result.headers().forEach(builder::header);
    JsonNode body = result.body();
    if (body == null) {
      return builder.build();
    }
    return builder.contentType(MediaType.APPLICATION_JSON).body(body.toString());
  }
}
