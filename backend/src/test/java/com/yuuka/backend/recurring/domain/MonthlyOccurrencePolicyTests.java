package com.yuuka.backend.recurring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class MonthlyOccurrencePolicyTests {
  private final MonthlyOccurrencePolicy policy = new MonthlyOccurrencePolicy();

  @Test
  void clampsDayThirtyOneToEachMonthsFinalDay() {
    assertThat(policy.occurrence(YearMonth.of(2026, 1), 31)).isEqualTo(LocalDate.of(2026, 1, 31));
    assertThat(policy.occurrence(YearMonth.of(2026, 2), 31)).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(policy.occurrence(YearMonth.of(2028, 2), 31)).isEqualTo(LocalDate.of(2028, 2, 29));
    assertThat(policy.occurrence(YearMonth.of(2026, 4), 31)).isEqualTo(LocalDate.of(2026, 4, 30));
  }

  @Test
  void includesOnlyOccurrencesInsideInclusiveRange() {
    assertThat(policy.occurrences(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 1), 31))
        .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28));
  }

  @Test
  void rejectsInvalidDueDaysAndRanges() {
    assertThatThrownBy(() -> policy.occurrence(YearMonth.of(2026, 1), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.occurrence(YearMonth.of(2026, 1), 32))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> policy.occurrences(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), 10))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
