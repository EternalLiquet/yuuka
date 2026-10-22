package com.yuuka.backend.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DNS-rebinding defense: a present {@code Origin} that is not allow-listed gets 403 before
 * authentication runs. Not a Spring bean, so it is only applied inside the MCP security chain.
 */
public class McpOriginFilter extends OncePerRequestFilter {
  private static final byte[] BODY =
      "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32000,\"message\":\"Forbidden\"}}"
          .getBytes(StandardCharsets.UTF_8);

  private final List<String> allowedOrigins;

  public McpOriginFilter(List<String> allowedOrigins) {
    this.allowedOrigins = List.copyOf(allowedOrigins);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String origin = request.getHeader("Origin");
    if (origin != null && !allowedOrigins.contains(origin)) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getOutputStream().write(BODY);
      return;
    }
    chain.doFilter(request, response);
  }
}
