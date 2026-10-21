package com.yuuka.backend.paycheck.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ProcessingAgePolicyTests {
  private final ProcessingAgePolicy policy = new ProcessingAgePolicy();
  private final LocalDate today = LocalDate.of(2026, 7, 19);

  @Test
  void entryProcessingExactlyThreeDaysIsAged() {
    assertThat(policy.isAged(LocalDate.of(2026, 7, 16), today)).isTrue();
  }

  @Test
  void entryProcessingTwoDaysIsNotYetAged() {
    assertThat(policy.isAged(LocalDate.of(2026, 7, 17), today)).isFalse();
  }

  @Test
  void entryProcessingLongerThanThreeDaysIsAged() {
    assertThat(policy.isAged(LocalDate.of(2026, 6, 1), today)).isTrue();
  }

  @Test
  void entryThatStartedProcessingTodayOrInTheFutureIsNotAged() {
    assertThat(policy.isAged(today, today)).isFalse();
    assertThat(policy.isAged(today.plusDays(1), today)).isFalse();
  }

  @Test
  void thresholdCrossesMonthAndLeapDayBoundaries() {
    assertThat(policy.isAged(LocalDate.of(2028, 2, 27), LocalDate.of(2028, 3, 1))).isTrue();
    assertThat(policy.isAged(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1))).isFalse();
    assertThat(ProcessingAgePolicy.AGED_AFTER_DAYS).isEqualTo(3);
  }
}
