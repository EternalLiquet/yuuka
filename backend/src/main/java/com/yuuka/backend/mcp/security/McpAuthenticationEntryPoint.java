package com.yuuka.backend.mcp.security;

import com.yuuka.backend.mcp.config.McpUrls;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 401 challenge for {@code /mcp}: {@code WWW-Authenticate: Bearer resource_metadata="<url>",
 * scope="yuuka:budget:read"} (RFC 9728 / MCP authorization). When a token was presented but is
 * invalid, {@code error="invalid_token"} is added (RFC 6750). Wraps Spring's {@link
 * BearerTokenAuthenticationEntryPoint} (status 401, no body) and replaces only the challenge
 * header.
 */
public final class McpAuthenticationEntryPoint implements AuthenticationEntryPoint {
  private final BearerTokenAuthenticationEntryPoint delegate =
      new BearerTokenAuthenticationEntryPoint();
  private final String metadataUrl;

  public McpAuthenticationEntryPoint(String resourceUri) {
    this.metadataUrl = McpUrls.metadataUrl(resourceUri);
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws java.io.IOException, jakarta.servlet.ServletException {
    delegate.commence(request, response, authException);
    boolean invalidToken = authException instanceof OAuth2AuthenticationException;
    StringBuilder challenge = new StringBuilder("Bearer ");
    if (invalidToken) {
      challenge.append("error=\"invalid_token\", ");
    }
    challenge
        .append("resource_metadata=\"")
        .append(metadataUrl)
        .append("\", scope=\"")
        .append(McpScopes.BUDGET_READ)
        .append('"');
    response.setHeader("WWW-Authenticate", challenge.toString());
  }
}
