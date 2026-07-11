package com.yuuka.backend.auth.application;

import com.yuuka.backend.common.config.AuthRateLimitProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthRateLimitService {
  private final AuthRateLimitProperties properties;
  private final Clock clock;
  private final ConcurrentMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

  public AuthRateLimitService(AuthRateLimitProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public void assertAllowed(String key) {
    String normalizedKey = normalizeKey(key);
    AttemptWindow window = attempts.get(normalizedKey);
    if (window == null) {
      return;
    }

    Instant now = clock.instant();
    if (window.isExpired(now)) {
      attempts.remove(normalizedKey, window);
      return;
    }

    if (window.isLocked(now)) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Too many authentication attempts");
    }
  }

  public void recordAttempt(String key) {
    String normalizedKey = normalizeKey(key);
    Instant now = clock.instant();
    reserveKeyCapacity(normalizedKey, now);
    attempts.compute(
        normalizedKey,
        (ignored, current) -> {
          AttemptWindow window =
              current == null || current.isExpired(now)
                  ? new AttemptWindow(now.plus(properties.window()))
                  : current;
          window.increment();
          if (window.count() >= properties.maxAttempts()) {
            window.lockUntil(now.plus(properties.window()));
          }
          return window;
        });
  }

  public void clear(String key) {
    attempts.remove(normalizeKey(key));
  }

  private String normalizeKey(String key) {
    return key.trim().toLowerCase(Locale.ROOT);
  }

  private void reserveKeyCapacity(String key, Instant now) {
    if (attempts.containsKey(key) || attempts.size() < properties.maxTrackedKeys()) {
      return;
    }
    attempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    if (attempts.size() >= properties.maxTrackedKeys()) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Authentication limiter capacity reached");
    }
  }

  private static final class AttemptWindow {
    private final Instant expiresAt;
    private int count;
    private Instant lockedUntil;

    private AttemptWindow(Instant expiresAt) {
      this.expiresAt = expiresAt;
    }

    private int count() {
      return count;
    }

    private void increment() {
      count += 1;
    }

    private void lockUntil(Instant lockedUntil) {
      this.lockedUntil = lockedUntil;
    }

    private boolean isExpired(Instant now) {
      return now.isAfter(expiresAt) && (lockedUntil == null || now.isAfter(lockedUntil));
    }

    private boolean isLocked(Instant now) {
      return lockedUntil != null && now.isBefore(lockedUntil);
    }
  }
}
