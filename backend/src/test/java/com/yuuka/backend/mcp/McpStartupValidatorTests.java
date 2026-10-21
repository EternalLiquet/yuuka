package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuuka.backend.mcp.config.McpProperties;
import com.yuuka.backend.mcp.config.McpStartupValidator;
import com.yuuka.backend.mcp.security.McpTokenDecoder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Context-level fail-closed checks that need no database. */
class McpStartupValidatorTests {
  private static final String MAPPING =
      "yuuka.mcp.owner-mappings[0].issuer=https://issuer.test.invalid";

  @Configuration
  @EnableConfigurationProperties(McpProperties.class)
  static class Base {
    @Bean
    McpStartupValidator validator(
        McpProperties properties,
        org.springframework.beans.factory.ObjectProvider<McpTokenDecoder> d) {
      return new McpStartupValidator(properties, d);
    }
  }

  @Configuration
  static class WithDecoder {
    @Bean
    McpTokenDecoder decoder() {
      return token -> {
        throw new UnsupportedOperationException("synthetic");
      };
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Base.class);

  @Test
  void disabledByDefaultStartsWithoutDecoder() {
    runner.run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void enabledWithoutApprovedDecoderFailsStartupInEveryProfile() {
    for (String profile : new String[] {"default", "prod", "dev", "test"}) {
      runner
          .withPropertyValues(
              "spring.profiles.active=" + profile,
              "yuuka.mcp.enabled=true",
              "yuuka.mcp.resource-uri=https://yuuka.test.invalid/mcp",
              MAPPING,
              "yuuka.mcp.owner-mappings[0].subject=s",
              "yuuka.mcp.owner-mappings[0].owner-id=00000000-0000-0000-0000-0000000000a1")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(
                        context.getStartupFailure().getMessage()
                            + rootMessage(context.getStartupFailure()))
                    .contains("requires the approved authentication configuration (#146)");
              });
    }
  }

  @Test
  void enabledWithDecoderButNoOwnerMappingsFailsStartup() {
    runner
        .withUserConfiguration(WithDecoder.class)
        .withPropertyValues(
            "yuuka.mcp.enabled=true", "yuuka.mcp.resource-uri=https://yuuka.test.invalid/mcp")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessage(context.getStartupFailure())).contains("owner-mappings");
            });
  }

  @Test
  void enabledWithDecoderButNoResourceUriFailsStartup() {
    runner
        .withUserConfiguration(WithDecoder.class)
        .withPropertyValues(
            "yuuka.mcp.enabled=true",
            MAPPING,
            "yuuka.mcp.owner-mappings[0].subject=s",
            "yuuka.mcp.owner-mappings[0].owner-id=00000000-0000-0000-0000-0000000000a1")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(rootMessage(context.getStartupFailure())).contains("resource-uri");
            });
  }

  @Test
  void enabledWithDecoderAndMappingsStarts() {
    runner
        .withUserConfiguration(WithDecoder.class)
        .withPropertyValues(
            "yuuka.mcp.enabled=true",
            "yuuka.mcp.resource-uri=https://yuuka.test.invalid/mcp",
            MAPPING,
            "yuuka.mcp.owner-mappings[0].subject=s",
            "yuuka.mcp.owner-mappings[0].owner-id=00000000-0000-0000-0000-0000000000a1")
        .run(context -> assertThat(context).hasNotFailed());
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return String.valueOf(current.getMessage());
  }
}
