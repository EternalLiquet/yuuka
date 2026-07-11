package com.yuuka.backend.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTests {
  @Test
  void rejectsLongLivedAccessTokens() {
    assertThatThrownBy(
            () ->
                new JwtProperties(
                    "yuuka",
                    "yuuka-api",
                    "a-secure-test-secret-that-is-at-least-32-bytes",
                    Duration.ofHours(2),
                    Duration.ofDays(30)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("one hour");
  }

  @Test
  void validatesAndNormalizesOwnerAuthenticationMaterial() {
    OwnerProperties owner =
        new OwnerProperties(
            "owner@yuuka.local",
            "$2a$12$.....................................................",
            null,
            "jbsw y3dp ehpk 3pxp");

    assertThat(owner.totpSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
    assertThat(owner.hasPasswordHash()).isTrue();
  }

  @Test
  void rejectsMalformedOwnerAuthenticationMaterial() {
    assertThatThrownBy(() -> new OwnerProperties("owner@yuuka.local", "plaintext", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BCrypt");
    assertThatThrownBy(() -> new OwnerProperties("owner@yuuka.local", null, null, "not-base32-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Base32");
  }
}
