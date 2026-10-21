package com.yuuka.backend.mcp.config;

import com.yuuka.backend.mcp.security.McpTokenDecoder;
import jakarta.annotation.PostConstruct;
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
  }
}
