package com.yuuka.backend.bucket.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuuka.backend.common.api.BusinessRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BucketCalculatorTests {
  private final BucketCalculator calculator = new BucketCalculator();

  @Test
  void includesPurchaseSpendingWithoutFloatingPointMath() {
    BucketMetrics metrics = calculator.calculate(5000, List.of(1235L, 910L));

    assertThat(metrics.spentMinor()).isEqualTo(2145);
    assertThat(metrics.remainingMinor()).isEqualTo(2855);
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
  void acceptsZeroBucketBudget() {
    BucketMetrics metrics = calculator.calculate(0, List.of());

    assertThat(metrics.budgetMinor()).isZero();
    assertThat(metrics.spentMinor()).isZero();
    assertThat(metrics.remainingMinor()).isZero();
    assertThat(metrics.overBudget()).isFalse();
  }

  @Test
  void rejectsNegativeBucketBudgets() {
    assertThatThrownBy(() -> calculator.calculate(-1, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Bucket budget must not be negative");
  }

  @Test
  void rejectsSpentTotalsThatExceedInt64MoneyRange() {
    assertThatThrownBy(() -> calculator.calculate(Long.MAX_VALUE, List.of(Long.MAX_VALUE, 1L)))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo("MONEY_AMOUNT_OVERFLOW"));
  }
}
