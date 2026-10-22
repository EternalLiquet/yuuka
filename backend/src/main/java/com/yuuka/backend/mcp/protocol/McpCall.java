package com.yuuka.backend.mcp.protocol;

import org.springframework.security.oauth2.jwt.Jwt;

/** One authenticated MCP POST: raw body, selected transport headers, and the token principal. */
public record McpCall(
    String body,
    String protocolVersionHeader,
    String methodHeader,
    String nameHeader,
    Jwt jwt,
    boolean hasReadScope) {}
