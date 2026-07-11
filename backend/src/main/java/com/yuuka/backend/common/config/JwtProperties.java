package com.yuuka.backend.common.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yuuka.jwt")
public record JwtProperties(
    String issuer,
    String audience,
    String secret,
    Duration accessTokenTtl,
    Duration refreshTokenTtl) {
  public JwtProperties {
    if (issuer == null || issuer.isBlank()) {
      throw new IllegalStateException("yuuka.jwt.issuer must not be blank");
    }
    if (audience == null || audience.isBlank()) {
      throw new IllegalStateException("yuuka.jwt.audience must not be blank");
    }
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("YUUKA_JWT_SECRET must be at least 32 bytes");
    }
    if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
      throw new IllegalStateException("yuuka.jwt.access-token-ttl must be positive");
    }
    if (accessTokenTtl.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalStateException("yuuka.jwt.access-token-ttl must not exceed one hour");
    }
    if (refreshTokenTtl == null || refreshTokenTtl.compareTo(Duration.ofDays(1)) < 0) {
      throw new IllegalStateException("yuuka.jwt.refresh-token-ttl must be at least one day");
    }
    if (refreshTokenTtl.compareTo(Duration.ofDays(90)) > 0) {
      throw new IllegalStateException("yuuka.jwt.refresh-token-ttl must not exceed 90 days");
    }
  }
}
