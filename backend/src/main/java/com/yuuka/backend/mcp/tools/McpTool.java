package com.yuuka.backend.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * A read-only MCP tool. Implementations are Spring beans collected by {@link McpToolRegistry}; the
 * owner is always supplied by the trusted principal, never read from arguments.
 */
public interface McpTool {
  String name();

  String title();

  String description();

  JsonNode inputSchema();

  JsonNode outputSchema();

  /** Throws {@link McpToolFailure} for tool-level failures. */
  McpReadEnvelope<?> call(UUID ownerId, JsonNode arguments);
}
