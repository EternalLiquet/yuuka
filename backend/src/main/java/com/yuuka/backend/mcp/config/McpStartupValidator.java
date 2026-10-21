package com.yuuka.backend.mcp.config;

import com.yuuka.backend.mcp.security.McpTokenDecoder;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Fails application startup when MCP is enabled without the approved authentication configuration.
 * Applies in every profile.
 */
@Component
public class McpStartupValidator {
  static final String MISSING_AUTH =
      "MCP read access requires the approved authentication configuration (#146).";

  private final McpProperties properties;
  private final ObjectProvider<McpTokenDecoder> tokenDecoder;

  public McpStartupValidator(McpProperties properties, ObjectProvider<McpTokenDecoder> decoder) {
    this.properties = properties;
    this.tokenDecoder = decoder;
  }

  @PostConstruct
  void validate() {
    if (!properties.enabled()) {
      return;
    }
    if (tokenDecoder.getIfAvailable() == null) {
      throw new IllegalStateException(MISSING_AUTH);
    }
    if (properties.ownerMappings().isEmpty()) {
      throw new IllegalStateException(
          "yuuka.mcp.owner-mappings must not be empty when MCP read access is enabled.");
    }
    if (properties.resourceUri() == null || properties.resourceUri().isBlank()) {
      throw new IllegalStateException(
          "yuuka.mcp.resource-uri must be set when MCP read access is enabled.");
    }
    URI resource = McpUrls.parse(properties.resourceUri());
    if (resource == null
        || !"https".equalsIgnoreCase(resource.getScheme())
        || resource.getRawQuery() != null
        || resource.getRawFragment() != null
        || !McpUrls.RESOURCE_PATH.equals(resource.getRawPath())) {
      throw new IllegalStateException(
          "yuuka.mcp.resource-uri must be an https URI whose path is exactly "
              + McpUrls.RESOURCE_PATH
              + " (no query or fragment).");
    }
    requireIssuerLikeUri("yuuka.mcp.issuer", properties.issuer());
    requireIssuerLikeUri("yuuka.mcp.jwk-set-uri", properties.jwkSetUri());
    for (String server : properties.authorizationServers()) {
      requireIssuerLikeUri("yuuka.mcp.authorization-servers", server);
    }
    if (properties.authorizationServers().isEmpty()) {
      throw new IllegalStateException(
          "yuuka.mcp.authorization-servers must not be empty when MCP read access is enabled.");
    }
    Duration skew = properties.clockSkew();
    if (skew.isNegative() || skew.compareTo(McpProperties.MAX_CLOCK_SKEW) > 0) {
      throw new IllegalStateException(
          "yuuka.mcp.clock-skew must be between PT0S and " + McpProperties.MAX_CLOCK_SKEW + ".");
    }
    for (McpProperties.OwnerMapping mapping : properties.ownerMappings()) {
      if (mapping.issuer() == null
          || !mapping.issuer().equals(properties.issuer())
          || mapping.subject() == null
          || mapping.subject().isBlank()
          || mapping.ownerId() == null) {
        throw new IllegalStateException(
            "Every yuuka.mcp.owner-mappings entry must have a subject, an owner-id and an issuer"
                + " equal to yuuka.mcp.issuer.");
      }
    }
  }

  /**
   * https is required; http is accepted only for loopback hosts (local tests, never production).
   */
  private static void requireIssuerLikeUri(String name, String value) {
    URI uri = McpUrls.parse(value);
    if (uri == null || !McpUrls.isHttpsOrLoopbackHttp(uri)) {
      throw new IllegalStateException(
          name + " must be an https URI (http is allowed only for loopback hosts).");
    }
  }
}
