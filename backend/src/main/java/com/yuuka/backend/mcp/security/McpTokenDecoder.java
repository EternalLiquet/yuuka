package com.yuuka.backend.mcp.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Validates an MCP access token (signature, issuer, audience, time bounds). Deliberately not a
 * Spring {@code JwtDecoder} type so it cannot replace the decoder used by the ordinary API chain.
 *
 * <p>Main code ships no implementation: production wiring is gated on issue #146. Tests provide a
 * synthetic implementation under {@code src/test} only.
 */
public interface McpTokenDecoder {
  Jwt decode(String token) throws JwtException;
}
