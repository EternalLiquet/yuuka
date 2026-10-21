package com.yuuka.backend.mcp.support;

import com.yuuka.backend.mcp.security.McpTokenDecoder;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only wiring of the synthetic decoder. Never component-scanned into production. */
@TestConfiguration(proxyBeanMethods = false)
public class SyntheticMcpAuthConfig {
  /** Primary so it wins if the production JWKS decoder is also registered by the properties. */
  @Bean
  @Primary
  McpTokenDecoder syntheticMcpTokenDecoder(Clock clock) {
    return SyntheticMcpTokens.decoder(clock);
  }
}
