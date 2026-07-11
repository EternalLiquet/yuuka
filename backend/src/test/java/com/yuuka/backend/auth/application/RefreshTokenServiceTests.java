package com.yuuka.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuuka.backend.auth.domain.RefreshToken;
import com.yuuka.backend.auth.infrastructure.JpaRefreshTokenRepository;
import com.yuuka.backend.common.config.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTests {
  @Mock private JpaRefreshTokenRepository refreshTokens;

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC);
  private RefreshTokenService service;

  @BeforeEach
  void setUp() {
    JwtProperties properties =
        new JwtProperties(
            "yuuka",
            "yuuka-api",
            "test-refresh-secret-with-at-least-32-bytes",
            Duration.ofMinutes(15),
            Duration.ofDays(30));
    service = new RefreshTokenService(refreshTokens, properties, clock);
  }

  @Test
  void revokesExpiredTokensDuringRotation() {
    String rawToken = "expired-token";
    RefreshToken expired =
        new RefreshToken(
            UUID.randomUUID(),
            UUID.randomUUID(),
            service.hash(rawToken),
            Instant.parse("2026-07-10T11:59:59Z"),
            Instant.parse("2026-07-01T12:00:00Z"));
    when(refreshTokens.findByTokenHash(service.hash(rawToken))).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service.rotate(rawToken))
        .isInstanceOf(InvalidRefreshTokenException.class);

    assertThat(expired.getRevokedAt()).isEqualTo(clock.instant());
  }

  @Test
  void revokedUnrotatedTokensDoNotRevokeTheWholeFamilyAgain() {
    String rawToken = "revoked-token";
    RefreshToken revoked =
        new RefreshToken(
            UUID.randomUUID(),
            UUID.randomUUID(),
            service.hash(rawToken),
            Instant.parse("2026-08-10T12:00:00Z"),
            Instant.parse("2026-07-01T12:00:00Z"));
    revoked.revoke(Instant.parse("2026-07-09T12:00:00Z"));
    when(refreshTokens.findByTokenHash(service.hash(rawToken))).thenReturn(Optional.of(revoked));

    assertThatThrownBy(() -> service.rotate(rawToken))
        .isInstanceOf(InvalidRefreshTokenException.class);

    verify(refreshTokens, never()).revokeFamily(any(), any());
  }
}
