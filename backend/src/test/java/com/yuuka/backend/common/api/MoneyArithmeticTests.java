package com.yuuka.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyArithmeticTests {
  @Test
  void convertsExactBigDecimalMoneyAggregateWithinInt64Range() {
    assertThat(MoneyArithmetic.toLongExact(BigDecimal.valueOf(Long.MAX_VALUE)))
        .isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void rejectsFractionalAggregateValues() {
    assertThatThrownBy(() -> MoneyArithmetic.toLongExact(new BigDecimal("1.5")))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo("MONEY_AMOUNT_OVERFLOW"));
  }

  @Test
  void rejectsAggregateValuesOutsideInt64Range() {
    BigDecimal tooLarge = BigDecimal.valueOf(Long.MAX_VALUE).add(BigDecimal.ONE);

    assertThatThrownBy(() -> MoneyArithmetic.toLongExact(tooLarge))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo("MONEY_AMOUNT_OVERFLOW"));
  }
}
