package com.yuuka.backend.mcp.security;

public final class McpScopes {
  public static final String BUDGET_READ = "yuuka:budget:read";
  public static final String BUDGET_READ_AUTHORITY = "SCOPE_" + BUDGET_READ;

  private McpScopes() {}
}
