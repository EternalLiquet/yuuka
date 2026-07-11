package com.yuuka.backend.bucket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class BucketCalculatorTests {
  private final BucketCalculator calculator = new BucketCalculator();

  @Test
  void includesSpendingAndNegativeCorrectionsWithoutFloatingPointMath() {
    BucketMetrics metrics = calculator.calculate(5000, List.of(1235L, 910L, -500L));

    assertThat(metrics.spentMinor()).isEqualTo(1645);
    assertThat(metrics.remainingMinor()).isEqualTo(3355);
    assertThat(metrics.overBudget()).isFalse();
  }

  @Test
  void reportsOverBudgetWithoutChangingTheBudget() {
    BucketMetrics metrics = calculator.calculate(5000, List.of(5100L));

    assertThat(metrics.spentMinor()).isEqualTo(5100);
    assertThat(metrics.remainingMinor()).isEqualTo(-100);
    assertThat(metrics.overBudget()).isTrue();
  }

  @Test
  void rejectsNegativeBucketBudgets() {
    assertThatThrownBy(() -> calculator.calculate(-1, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Bucket budget must not be negative");
  }
}
