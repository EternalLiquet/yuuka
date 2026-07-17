package com.yuuka.backend.bucket.application;

import com.yuuka.backend.bucket.api.dto.RollingSpendingBucketPerformanceResponse;
import com.yuuka.backend.bucket.api.dto.SpendingBucketPerformanceSummaryResponse;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.bucket.infrastructure.SpendingBucketPerformanceProjection;
import com.yuuka.backend.common.api.MoneyArithmetic;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpendingBucketPerformanceService {
  public static final int THIRTY_DAY_WINDOW = 30;
  public static final int NINETY_DAY_WINDOW = 90;

  private final JpaBucketTransactionRepository transactions;

  public SpendingBucketPerformanceService(JpaBucketTransactionRepository transactions) {
    this.transactions = transactions;
  }

  @Transactional(readOnly = true)
  public SpendingBucketPerformanceSummaryResponse paycheckSummary(
      UUID ownerId, UUID paycheckId, LocalDate asOfDate) {
    SpendingBucketPerformanceProjection aggregate =
        transactions.aggregatePaycheckPerformance(ownerId, paycheckId, asOfDate);
    return toSummary(aggregate);
  }

  @Transactional(readOnly = true)
  public RollingSpendingBucketPerformanceResponse rolling90Days(UUID ownerId, LocalDate asOfDate) {
    return rollingDays(ownerId, asOfDate, NINETY_DAY_WINDOW);
  }

  @Transactional(readOnly = true)
  public RollingSpendingBucketPerformanceResponse rollingDays(
      UUID ownerId, LocalDate asOfDate, int days) {
    if (!isSupportedRollingWindow(days)) {
      throw new IllegalArgumentException("Rolling Spending Bucket window must be 30 or 90 days.");
    }
    LocalDate windowStart = asOfDate.minusDays(days - 1L);
    SpendingBucketPerformanceProjection aggregate =
        transactions.aggregateRollingPerformance(ownerId, windowStart, asOfDate);
    return new RollingSpendingBucketPerformanceResponse(
        asOfDate, windowStart, asOfDate, value(aggregate.getPaycheckCount()), toSummary(aggregate));
  }

  public static boolean isSupportedRollingWindow(int days) {
    return days == THIRTY_DAY_WINDOW || days == NINETY_DAY_WINDOW;
  }

  private SpendingBucketPerformanceSummaryResponse toSummary(
      SpendingBucketPerformanceProjection aggregate) {
    if (value(aggregate.getBucketCount()) == 0) {
      return null;
    }
    long budgeted = MoneyArithmetic.toLongExact(aggregate.getBudgetedMinor());
    long spent = MoneyArithmetic.toLongExact(aggregate.getSpentMinor());
    return new SpendingBucketPerformanceSummaryResponse(
        budgeted, spent, MoneyArithmetic.subtract(budgeted, spent));
  }

  private long value(Long value) {
    return value == null ? 0 : value;
  }
}
