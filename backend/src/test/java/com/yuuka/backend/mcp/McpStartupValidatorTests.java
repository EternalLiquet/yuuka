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

  private static final String[] VALID = {
    "yuuka.mcp.enabled=true",
    "yuuka.mcp.resource-uri=https://yuuka.test.invalid/mcp",
    "yuuka.mcp.issuer=https://issuer.test.invalid",
    "yuuka.mcp.jwk-set-uri=https://issuer.test.invalid/jwks",
    MAPPING,
    "yuuka.mcp.owner-mappings[0].subject=s",
    "yuuka.mcp.owner-mappings[0].owner-id=00000000-0000-0000-0000-0000000000a1"
  };

  /** Valid configuration with entries whose property name matches an override replaced. */
  private ApplicationContextRunner withOverrides(String... overrides) {
    java.util.List<String> values = new java.util.ArrayList<>();
    outer:
    for (String base : VALID) {
      for (String override : overrides) {
        if (base.substring(0, base.indexOf('='))
            .equals(override.substring(0, override.indexOf('=')))) continue outer;
      }
      values.add(base);
    }
    values.addAll(java.util.List.of(overrides));
    return runner
        .withUserConfiguration(WithDecoder.class)
        .withPropertyValues(values.toArray(String[]::new));
  }

  private void assertFailsWith(ApplicationContextRunner configured, String expected) {
    configured.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(rootMessage(context.getStartupFailure())).contains(expected);
        });
  }

  @Test
  void enabledWithFullConfigurationStarts() {
    withOverrides().run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  void resourceUriMustBeHttpsWithMcpPath() {
    assertFailsWith(
        withOverrides("yuuka.mcp.resource-uri=http://yuuka.test.invalid/mcp"), "resource-uri");
    assertFailsWith(withOverrides("yuuka.mcp.resource-uri=http://localhost/mcp"), "resource-uri");
    assertFailsWith(
        withOverrides("yuuka.mcp.resource-uri=https://yuuka.test.invalid/other"), "resource-uri");
    assertFailsWith(
        withOverrides("yuuka.mcp.resource-uri=https://yuuka.test.invalid/mcp?x=1"), "resource-uri");
  }

  @Test
  void issuerAndJwkSetUriMustBeConfiguredAndHttps() {
    assertFailsWith(withOverrides("yuuka.mcp.issuer="), "yuuka.mcp.issuer");
    assertFailsWith(withOverrides("yuuka.mcp.jwk-set-uri="), "yuuka.mcp.jwk-set-uri");
    assertFailsWith(
        withOverrides("yuuka.mcp.jwk-set-uri=http://issuer.test.invalid/jwks"),
        "yuuka.mcp.jwk-set-uri");
    assertFailsWith(
        withOverrides(
            "yuuka.mcp.issuer=http://issuer.test.invalid",
            "yuuka.mcp.owner-mappings[0].issuer=http://issuer.test.invalid"),
        "yuuka.mcp.issuer");
    assertFailsWith(withOverrides("yuuka.mcp.jwk-set-uri=not a uri"), "yuuka.mcp.jwk-set-uri");
  }

  @Test
  void httpIsAllowedOnlyForLoopbackHosts() {
    for (String host : new String[] {"localhost", "127.0.0.1", "[::1]"}) {
      withOverrides(
              "yuuka.mcp.issuer=http://" + host + ":9000",
              "yuuka.mcp.jwk-set-uri=http://" + host + ":9000/jwks",
              "yuuka.mcp.owner-mappings[0].issuer=http://" + host + ":9000")
          .run(context -> assertThat(context).hasNotFailed());
    }
    assertFailsWith(
        withOverrides("yuuka.mcp.jwk-set-uri=http://192.0.2.10/jwks"), "yuuka.mcp.jwk-set-uri");
    assertFailsWith(
        withOverrides("yuuka.mcp.jwk-set-uri=http://localhost.evil.test/jwks"),
        "yuuka.mcp.jwk-set-uri");
  }

  @Test
  void ownerMappingIssuerMustEqualConfiguredIssuer() {
    assertFailsWith(
        withOverrides("yuuka.mcp.owner-mappings[0].issuer=https://other.test.invalid"),
        "issuer equal to yuuka.mcp.issuer");
  }

  @Test
  void ownerMappingNeedsSubjectAndOwnerId() {
    assertFailsWith(withOverrides("yuuka.mcp.owner-mappings[0].owner-id="), "owner-id");
    assertFailsWith(withOverrides("yuuka.mcp.owner-mappings[0].subject="), "subject");
  }

  @Test
  void clockSkewIsBounded() {
    withOverrides("yuuka.mcp.clock-skew=PT0S").run(c -> assertThat(c).hasNotFailed());
    withOverrides("yuuka.mcp.clock-skew=PT5M").run(c -> assertThat(c).hasNotFailed());
    assertFailsWith(withOverrides("yuuka.mcp.clock-skew=PT5M1S"), "clock-skew");
    assertFailsWith(withOverrides("yuuka.mcp.clock-skew=-PT1S"), "clock-skew");
  }

  @Test
  void authorizationServersDefaultToTheIssuerAndMustBeHttps() {
    assertFailsWith(
        withOverrides("yuuka.mcp.authorization-servers[0]=http://as.test.invalid"),
        "authorization-servers");
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return String.valueOf(current.getMessage());
  }
}
