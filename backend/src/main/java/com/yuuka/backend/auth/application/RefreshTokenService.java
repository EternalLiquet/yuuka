package com.yuuka.backend.auth.application;

import com.yuuka.backend.auth.domain.RefreshToken;
import com.yuuka.backend.auth.infrastructure.JpaRefreshTokenRepository;
import com.yuuka.backend.common.config.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
  private static final int TOKEN_BYTES = 48;

  private final JpaRefreshTokenRepository refreshTokens;
  private final JwtProperties properties;
  private final Clock clock;
  private final SecureRandom secureRandom = new SecureRandom();

  public RefreshTokenService(
      JpaRefreshTokenRepository refreshTokens, JwtProperties properties, Clock clock) {
    this.refreshTokens = refreshTokens;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public IssuedRefreshToken issue(UUID userId) {
    return create(userId, UUID.randomUUID());
  }

  @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
  public RotatedRefreshToken rotate(String rawToken) {
    Instant now = clock.instant();
    RefreshToken current =
        refreshTokens
            .findByTokenHash(hash(rawToken))
            .orElseThrow(InvalidRefreshTokenException::new);

    if (current.isRevoked()) {
      if (current.wasRotated()) {
        refreshTokens.revokeFamily(current.getFamilyId(), now);
      }
      throw new InvalidRefreshTokenException();
    }
    if (current.isExpired(now)) {
      current.revoke(now);
      throw new InvalidRefreshTokenException();
    }

    IssuedRefreshToken replacement = create(current.getUserId(), current.getFamilyId());
    current.rotateTo(replacement.id(), now);
    return new RotatedRefreshToken(
        current.getUserId(), replacement.rawToken(), replacement.expiresAt());
  }

  @Transactional
  public void revoke(String rawToken) {
    refreshTokens.findByTokenHash(hash(rawToken)).ifPresent(token -> token.revoke(clock.instant()));
  }

  private IssuedRefreshToken create(UUID userId, UUID familyId) {
    Instant now = clock.instant();
    String rawToken = generateToken();
    Instant expiresAt = now.plus(properties.refreshTokenTtl());
    RefreshToken token =
        refreshTokens.saveAndFlush(
            new RefreshToken(userId, familyId, hash(rawToken), expiresAt, now));
    return new IssuedRefreshToken(token.getId(), rawToken, expiresAt);
  }

  private String generateToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record IssuedRefreshToken(UUID id, String rawToken, Instant expiresAt) {}

  public record RotatedRefreshToken(UUID userId, String rawToken, Instant expiresAt) {}
}
