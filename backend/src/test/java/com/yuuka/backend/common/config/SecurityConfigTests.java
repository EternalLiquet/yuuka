package com.yuuka.backend.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.domain.UserRole;
import com.yuuka.backend.common.security.JwtTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class SecurityConfigTests {
  private static final Instant START = Instant.parse("2026-07-15T03:59:00Z");

  private final JwtProperties properties =
      new JwtProperties(
          "yuuka",
          "yuuka-api",
          "test-jwt-secret-with-at-least-32-bytes",
          Duration.ofMinutes(15),
          Duration.ofDays(30));
  private final MutableClock clock = new MutableClock(START);
  private JwtEncoder encoder;
  private JwtDecoder decoder;

  @BeforeEach
  void setUp() {
    SecurityConfig config = new SecurityConfig(null, null);
    SecretKey secretKey = config.jwtSecretKey(properties);
    encoder = config.jwtEncoder(secretKey);
    decoder = config.jwtDecoder(secretKey, properties, clock);
  }

  @Test
  void acceptsTokenCreatedAtTheInjectedClockTime() {
    String token = tokenService().createAccessToken(user()).value();

    assertThat(decoder.decode(token).getSubject()).isEqualTo(userId().toString());
  }

  @Test
  void rejectsTokensWithInvalidIssuerOrAudience() {
    assertThatThrownBy(
            () ->
                decoder.decode(
                    encodeToken(
                        "not-yuuka",
                        List.of(properties.audience()),
                        START,
                        START.plus(properties.accessTokenTtl()))))
        .hasMessageContaining("iss claim is not valid");

    assertThatThrownBy(
            () ->
                decoder.decode(
                    encodeToken(
                        properties.issuer(),
                        List.of("not-yuuka-api"),
                        START,
                        START.plus(properties.accessTokenTtl()))))
        .hasMessageContaining("aud claim is not valid");
  }

  @Test
  void rejectsTokenAfterTheInjectedClockAdvancesBeyondExpiration() {
    String token = tokenService().createAccessToken(user()).value();

    clock.setInstant(START.plus(properties.accessTokenTtl()).plusSeconds(61));

    assertThatThrownBy(() -> decoder.decode(token)).hasMessageContaining("Jwt expired");
  }

  @Test
  void rejectsTokenBeforeNotBeforeAtTheInjectedClockTime() {
    String token =
        encodeToken(
            properties.issuer(),
            List.of(properties.audience()),
            START,
            START.plus(properties.accessTokenTtl()),
            START.plusSeconds(61));

    assertThatThrownBy(() -> decoder.decode(token)).hasMessageContaining("Jwt used before");
  }

  private JwtTokenService tokenService() {
    return new JwtTokenService(encoder, properties, clock);
  }

  private String encodeToken(
      String issuer, List<String> audience, Instant issuedAt, Instant expiresAt) {
    return encodeToken(issuer, audience, issuedAt, expiresAt, null);
  }

  private String encodeToken(
      String issuer,
      List<String> audience,
      Instant issuedAt,
      Instant expiresAt,
      Instant notBefore) {
    JwtClaimsSet.Builder builder =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(userId().toString())
            .audience(audience)
            .claim("roles", List.of(UserRole.USER.authority()));
    if (notBefore != null) {
      builder.notBefore(notBefore);
    }
    JwtClaimsSet claims = builder.build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private UserAccount user() {
    UserAccount user = mock(UserAccount.class);
    when(user.getId()).thenReturn(userId());
    when(user.getEmail()).thenReturn("jwt-clock@yuuka.local");
    when(user.getRole()).thenReturn(UserRole.USER);
    return user;
  }

  private UUID userId() {
    return UUID.fromString("11111111-1111-4111-8111-111111111111");
  }

  private static class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
