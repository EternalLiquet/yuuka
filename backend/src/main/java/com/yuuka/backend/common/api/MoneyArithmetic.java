package com.yuuka.backend.common.api;

import java.util.Map;
import java.util.stream.LongStream;

public final class MoneyArithmetic {
  private MoneyArithmetic() {}

  public static long add(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException exception) {
      throw overflow();
    }
  }

  public static long subtract(long left, long right) {
    try {
      return Math.subtractExact(left, right);
    } catch (ArithmeticException exception) {
      throw overflow();
    }
  }

  public static long sum(LongStream values) {
    return values.reduce(0, MoneyArithmetic::add);
  }

  public static BusinessRuleException overflow() {
    return new BusinessRuleException(
        "MONEY_AMOUNT_OVERFLOW",
        "The amounts in this request are too large to calculate safely.",
        Map.of("currencyCode", "USD"));
  }
}
