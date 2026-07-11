package com.yuuka.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TotpServiceTests {
  @Test
  void verifiesKnownTotpCode() {
    TotpService service = new TotpService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

    assertThat(service.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082")).isTrue();
  }

  @Test
  void rejectsMalformedTotpCode() {
    TotpService service = new TotpService(Clock.systemUTC());

    assertThat(service.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "12ab56")).isFalse();
  }

  @Test
  void rejectsNullAndWrongTotpInputs() {
    TotpService service = new TotpService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

    assertThat(service.verify(null, "287082")).isFalse();
    assertThat(service.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", null)).isFalse();
    assertThat(service.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "000000")).isFalse();
  }

  @Test
  void acceptsNormalizedBase32SecretsAndRejectsInvalidCharacters() {
    TotpService service = new TotpService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));

    assertThat(service.verify(" gezd gnbv gy3t qojq gezd gnbv gy3t qojq==== ", "287082")).isTrue();
    assertThatThrownBy(() -> service.verify("!!!!!!!!", "123456"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TOTP secret must be Base32 encoded");
  }
}
