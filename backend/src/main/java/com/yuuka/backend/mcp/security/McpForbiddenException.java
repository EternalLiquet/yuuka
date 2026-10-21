package com.yuuka.backend.mcp.security;

/** The caller is authenticated but not allowed to use MCP. Carries no detail by design. */
public class McpForbiddenException extends RuntimeException {
  public McpForbiddenException() {
    super("Forbidden", null, false, false);
  }
}
