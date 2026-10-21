package com.yuuka.backend.mcp.security;

import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.config.McpProperties;
import com.yuuka.backend.mcp.config.McpUrls;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Dedicated, fail-closed security chain for {@code /mcp}. The chain denies everything unless MCP is
 * enabled AND an approved {@link McpTokenDecoder} bean exists. It is independent of the ordinary
 * owner-JWT chain, so owner access tokens are never accepted here and MCP tokens are never accepted
 * by the REST API.
 */
@Configuration
public class McpSecurityConfig {
  @Bean
  @Order(1)
  public SecurityFilterChain mcpSecurityFilterChain(
      HttpSecurity http, McpProperties properties, ObjectProvider<McpTokenDecoder> decoders)
      throws Exception {
    http.securityMatcher("/mcp", "/mcp/**")
        // CSRF is unnecessary: /mcp accepts only bearer tokens from the Authorization header (the
        // default BearerTokenResolver ignores query and form tokens), the chain is STATELESS so no
        // session or cookie can authenticate, CORS is not enabled, and a disallowed Origin is
        // rejected before authentication. Proven by McpBrowserCredentialTests.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .cors(AbstractHttpConfigurer::disable);
    http.addFilterBefore(
        new McpOriginFilter(properties.allowedOrigins()), BearerTokenAuthenticationFilter.class);
    McpTokenDecoder decoder = properties.enabled() ? decoders.getIfAvailable() : null;
    if (decoder == null) {
      return http.authorizeHttpRequests(a -> a.anyRequest().denyAll())
          .exceptionHandling(
              e ->
                  e.authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                      .accessDeniedHandler(new BearerTokenAccessDeniedHandler()))
          .build();
    }
    JwtDecoder adapter = adapt(decoder, properties.resourceUri());
    return http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .oauth2ResourceServer(
            o ->
                o.authenticationEntryPoint(
                        new McpAuthenticationEntryPoint(properties.resourceUri()))
                    .jwt(j -> j.decoder(adapter).jwtAuthenticationConverter(scopeConverter())))
        .build();
  }

  /**
   * Metadata-only chain (ADR 4.3): matches exactly {@code GET
   * /.well-known/oauth-protected-resource/mcp} and nothing else. Anonymous by design, with no
   * resource server, so a stale or garbage {@code Authorization} header cannot turn discovery into
   * a 401. Stateless. CSRF stays at the Spring default (safe methods are ignored). The Origin
   * filter is intentionally not applied: the document is static and public, and Origin validation
   * protects {@code /mcp} calls. Registered only when MCP is enabled; otherwise the path falls
   * through to the ordinary REST chain (401).
   */
  @Bean
  @Order(0)
  @ConditionalOnMcpEnabled
  public SecurityFilterChain mcpMetadataSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, McpUrls.METADATA_PATH))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }

  /**
   * Authorities come only from the standard {@code scope}/{@code scp} claims with a {@code SCOPE_}
   * prefix. Not a bean, so it cannot replace the ordinary chain's roles-based converter.
   */
  static JwtAuthenticationConverter scopeConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthorityPrefix("SCOPE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }

  /** Not a bean: wraps the approved decoder and re-checks the audience as defense in depth. */
  static JwtDecoder adapt(McpTokenDecoder decoder, String resourceUri) {
    return token -> {
      Jwt jwt = decoder.decode(token);
      List<String> audience = jwt.getAudience();
      if (audience == null || resourceUri == null || !audience.contains(resourceUri)) {
        throw new JwtException("Token audience does not match this resource");
      }
      return jwt;
    };
  }
}
