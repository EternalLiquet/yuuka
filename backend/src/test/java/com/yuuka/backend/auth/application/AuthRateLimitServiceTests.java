package com.yuuka.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuuka.backend.common.config.AuthRateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AuthRateLimitServiceTests {
  @Test
  void locksAfterTheConfiguredAttemptsAndClearUnlocksTheKey() {
    AuthRateLimitService service =
        new AuthRateLimitService(
            new AuthRateLimitProperties(2, Duration.ofMinutes(15), 100),
            Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneId.of("UTC")));

    service.recordAttempt(" Email:Owner@Yuuka.Local ");
    service.recordAttempt("email:owner@yuuka.local");
    assertThatThrownBy(() -> service.assertAllowed("EMAIL:OWNER@YUUKA.LOCAL"))
        .isInstanceOf(ResponseStatusException.class);

    service.clear("email:owner@yuuka.local");
    assertThatCode(() -> service.assertAllowed("email:owner@yuuka.local"))
        .doesNotThrowAnyException();
  }

  @Test
  void expiredWindowsAreRemovedBeforeTheyCanBlockAuthentication() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-10T12:00:00Z"));
    AuthRateLimitService service =
        new AuthRateLimitService(new AuthRateLimitProperties(1, Duration.ofMinutes(1), 100), clock);
    service.recordAttempt("client:one");
    clock.set(Instant.parse("2026-07-10T12:02:00Z"));

    assertThatCode(() -> service.assertAllowed("client:one")).doesNotThrowAnyException();
  }

  @Test
  void boundedKeyStorageFailsClosedAndReclaimsExpiredCapacity() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-10T12:00:00Z"));
    AuthRateLimitService service =
        new AuthRateLimitService(new AuthRateLimitProperties(5, Duration.ofMinutes(1), 100), clock);
    for (int index = 0; index < 100; index++) {
      service.recordAttempt("client:" + index);
    }

    assertThatThrownBy(() -> service.recordAttempt("client:overflow"))
        .isInstanceOf(ResponseStatusException.class);

    clock.set(Instant.parse("2026-07-10T12:02:00Z"));
    assertThatCode(() -> service.recordAttempt("client:replacement")).doesNotThrowAnyException();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant value) {
      instant = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
