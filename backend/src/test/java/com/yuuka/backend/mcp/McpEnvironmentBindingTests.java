package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuuka.backend.mcp.config.McpProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Environment-variable (relaxed) binding of the MCP settings, as Compose delivers them. */
class McpEnvironmentBindingTests {
  @Configuration
  @EnableConfigurationProperties(McpProperties.class)
  static class Config {}

  private ApplicationContextRunner withEnvironment(Map<String, Object> env) {
    return new ApplicationContextRunner()
        .withUserConfiguration(Config.class)
        .withInitializer(
            context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "synthetic-systemEnvironment", new java.util.HashMap<>(env))));
  }

  @Test
  void ownerMappingsAndSettingsBindFromEnvironmentVariables() {
    withEnvironment(
            Map.of(
                "YUUKA_MCP_ENABLED", "true",
                "YUUKA_MCP_RESOURCE_URI", "https://yuuka.test.invalid/mcp",
                "YUUKA_MCP_ISSUER", "https://issuer.test.invalid",
                "YUUKA_MCP_JWK_SET_URI", "https://issuer.test.invalid/jwks",
                "YUUKA_MCP_CLOCK_SKEW", "PT30S",
                "YUUKA_MCP_OWNERMAPPINGS_0_ISSUER", "https://issuer.test.invalid",
                "YUUKA_MCP_OWNERMAPPINGS_0_SUBJECT", "subject-env",
                "YUUKA_MCP_OWNERMAPPINGS_0_OWNERID", "00000000-0000-0000-0000-0000000000a1"))
        .run(
            context -> {
              McpProperties properties = context.getBean(McpProperties.class);
              assertThat(properties.enabled()).isTrue();
              assertThat(properties.resourceUri()).isEqualTo("https://yuuka.test.invalid/mcp");
              assertThat(properties.issuer()).isEqualTo("https://issuer.test.invalid");
              assertThat(properties.jwkSetUri()).isEqualTo("https://issuer.test.invalid/jwks");
              assertThat(properties.authorizationServers())
                  .containsExactly("https://issuer.test.invalid");
              assertThat(properties.clockSkew()).isEqualTo(Duration.ofSeconds(30));
              assertThat(properties.ownerMappings())
                  .containsExactly(
                      new McpProperties.OwnerMapping(
                          "https://issuer.test.invalid",
                          "subject-env",
                          UUID.fromString("00000000-0000-0000-0000-0000000000a1")));
            });
  }

  @Test
  void defaultsApplyWhenNothingIsConfigured() {
    withEnvironment(Map.of())
        .run(
            context -> {
              McpProperties properties = context.getBean(McpProperties.class);
              assertThat(properties.enabled()).isFalse();
              assertThat(properties.clockSkew()).isEqualTo(Duration.ofSeconds(60));
              assertThat(properties.ownerMappings()).isEmpty();
              assertThat(properties.authorizationServers()).isEmpty();
            });
  }

  @Test
  void emptyComposePassthroughValuesDoNotBreakStartupWhileDisabled() {
    withEnvironment(
            Map.of(
                "YUUKA_MCP_ENABLED", "false",
                "YUUKA_MCP_RESOURCE_URI", "",
                "YUUKA_MCP_ISSUER", "",
                "YUUKA_MCP_JWK_SET_URI", "",
                "YUUKA_MCP_OWNERMAPPINGS_0_ISSUER", "",
                "YUUKA_MCP_OWNERMAPPINGS_0_SUBJECT", "",
                "YUUKA_MCP_OWNERMAPPINGS_0_OWNERID", ""))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(McpProperties.class).enabled()).isFalse();
            });
  }
}
