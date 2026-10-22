package com.yuuka.backend.mcp.security;

import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.config.McpProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the production JWKS-backed decoder only when MCP is enabled AND both the issuer and the
 * JWKS URI are configured. The bean type is {@link McpTokenDecoder}, deliberately not a Spring
 * {@code JwtDecoder}, so it cannot affect the ordinary REST chain.
 */
@Configuration(proxyBeanMethods = false)
public class McpDecoderConfig {
  @Bean
  @ConditionalOnMcpEnabled
  @ConditionalOnExpression(
      "!'${yuuka.mcp.issuer:}'.isBlank() && !'${yuuka.mcp.jwk-set-uri:}'.isBlank()")
  McpTokenDecoder jwksMcpTokenDecoder(McpProperties properties, Clock clock) {
    return new JwksMcpTokenDecoder(
        properties.issuer(),
        properties.jwkSetUri(),
        properties.resourceUri(),
        properties.clockSkew(),
        clock);
  }
}
