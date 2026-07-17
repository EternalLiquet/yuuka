package com.yuuka.backend.paycheck.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PaycheckCalculator {
  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

  public PaycheckMetrics calculate(long paycheckAmountMinor, List<AllocationLine> lines) {
    if (paycheckAmountMinor < 0) {
      throw new IllegalArgumentException("Paycheck amount must not be negative");
    }

    long allocated = 0;
    long posted = 0;
    long processing = 0;
    long notPaid = 0;
    long postedCount = 0;
    long processingCount = 0;
    long notPaidCount = 0;

    for (AllocationLine line : lines) {
      if (line.deleted()) {
        continue;
      }
      allocated = Math.addExact(allocated, line.amountMinor());
      switch (line.status()) {
        case POSTED -> {
          posted = Math.addExact(posted, line.amountMinor());
          postedCount++;
        }
        case PROCESSING -> {
          processing = Math.addExact(processing, line.amountMinor());
          processingCount++;
        }
        case NOT_PAID -> {
          notPaid = Math.addExact(notPaid, line.amountMinor());
          notPaidCount++;
        }
      }
    }

    return new PaycheckMetrics(
        allocated,
        Math.subtractExact(paycheckAmountMinor, allocated),
        posted,
        processing,
        notPaid,
        postedCount,
        processingCount,
        notPaidCount,
        percent(allocated, paycheckAmountMinor),
        percent(posted, allocated));
  }

  public PaycheckMetrics calculateFromTotals(
      long paycheckAmountMinor,
      long allocated,
      long posted,
      long processing,
      long notPaid,
      long postedCount,
      long processingCount,
      long notPaidCount) {
    if (paycheckAmountMinor < 0) {
      throw new IllegalArgumentException("Paycheck amount must not be negative");
    }
    if (allocated < 0
        || posted < 0
        || processing < 0
        || notPaid < 0
        || postedCount < 0
        || processingCount < 0
        || notPaidCount < 0) {
      throw new IllegalArgumentException("Paycheck totals must not be negative");
    }
    if (Math.addExact(Math.addExact(posted, processing), notPaid) != allocated) {
      throw new IllegalArgumentException("Paycheck status totals must match allocation total");
    }
    return new PaycheckMetrics(
        allocated,
        Math.subtractExact(paycheckAmountMinor, allocated),
        posted,
        processing,
        notPaid,
        postedCount,
        processingCount,
        notPaidCount,
        percent(allocated, paycheckAmountMinor),
        percent(posted, allocated));
  }

  private BigDecimal percent(long numerator, long denominator) {
    if (denominator == 0) {
      return BigDecimal.ZERO.setScale(2);
    }
    return BigDecimal.valueOf(numerator)
        .multiply(ONE_HUNDRED)
        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
  }
}
