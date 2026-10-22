package com.yuuka.backend.mcp.api;

import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.config.McpProperties;
import com.yuuka.backend.mcp.config.McpUrls;
import com.yuuka.backend.mcp.security.McpScopes;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 9728 protected-resource metadata. Static, non-personal JSON served by the dedicated
 * metadata-only security chain; it carries no owner data and no counts. Registered only when MCP is
 * enabled. Authorization-server metadata is not served by Yuuka.
 */
@Hidden
@RestController
@ConditionalOnMcpEnabled
public class McpProtectedResourceMetadataController {
  private final Map<String, Object> metadata;

  public McpProtectedResourceMetadataController(McpProperties properties) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("resource", properties.resourceUri());
    document.put("authorization_servers", List.copyOf(properties.authorizationServers()));
    document.put("scopes_supported", List.of(McpScopes.BUDGET_READ));
    document.put("bearer_methods_supported", List.of("header"));
    this.metadata = java.util.Collections.unmodifiableMap(document);
  }

  @GetMapping(McpUrls.METADATA_PATH)
  public ResponseEntity<Map<String, Object>> metadata() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(metadata);
  }
}
