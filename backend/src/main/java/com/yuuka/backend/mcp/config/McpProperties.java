package com.yuuka.backend.mcp.config;

import java.time.Duration;
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
 * @param issuer expected {@code iss} of access tokens (the external authorization server)
 * @param jwkSetUri JWKS endpoint of that authorization server; fetched lazily, never at startup
 * @param authorizationServers advertised in the protected-resource metadata; defaults to {@code
 *     [issuer]}
 * @param clockSkew explicit tolerance applied to {@code exp}/{@code nbf}; default 60 s, bounded 0
 *     to 5 minutes
 */
@ConfigurationProperties(prefix = "yuuka.mcp")
public record McpProperties(
    boolean enabled,
    String resourceUri,
    List<OwnerMapping> ownerMappings,
    List<String> allowedOrigins,
    String issuer,
    String jwkSetUri,
    List<String> authorizationServers,
    Duration clockSkew) {
  public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);
  public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

  public McpProperties {
    ownerMappings = ownerMappings == null ? List.of() : List.copyOf(ownerMappings);
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    if (authorizationServers == null || authorizationServers.isEmpty()) {
      authorizationServers = issuer == null || issuer.isBlank() ? List.of() : List.of(issuer);
    } else {
      authorizationServers = List.copyOf(authorizationServers);
    }
    clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
  }

  public record OwnerMapping(String issuer, String subject, UUID ownerId) {}
}
