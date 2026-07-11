package com.yuuka.backend.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yuuka.auth.rate-limit")
public record AuthRateLimitProperties(int maxAttempts, Duration window, int maxTrackedKeys) {
  public AuthRateLimitProperties {
    if (maxAttempts < 1) {
      throw new IllegalStateException("yuuka.auth.rate-limit.max-attempts must be at least 1");
    }
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalStateException("yuuka.auth.rate-limit.window must be positive");
    }
    if (maxTrackedKeys < 100) {
      throw new IllegalStateException(
          "yuuka.auth.rate-limit.max-tracked-keys must be at least 100");
    }
  }
}
