package com.yuuka.backend.mcp.config;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the read-only MCP surface. The feature is disabled by default and cannot be
 * enabled without an approved {@code McpTokenDecoder} (issue #146).
 *
 * @param enabled master switch; false by default
 * @param resourceUri canonical resource URI that access tokens must be issued for (audience)
 * @param ownerMappings exact (issuer, subject) to owner mappings; there is no implicit mapping
 * @param allowedOrigins browser origins allowed to call the endpoint; empty by default
 */
@ConfigurationProperties(prefix = "yuuka.mcp")
public record McpProperties(
    boolean enabled,
    String resourceUri,
    List<OwnerMapping> ownerMappings,
    List<String> allowedOrigins) {
  public McpProperties {
    ownerMappings = ownerMappings == null ? List.of() : List.copyOf(ownerMappings);
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }

  public record OwnerMapping(String issuer, String subject, UUID ownerId) {}
}
