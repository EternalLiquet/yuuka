package com.yuuka.backend.bucket.application;

import com.yuuka.backend.bucket.api.dto.RollingSpendingBucketPerformanceResponse;
import com.yuuka.backend.bucket.api.dto.SpendingBucketPerformanceSummaryResponse;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.bucket.infrastructure.SpendingBucketPerformanceProjection;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpendingBucketPerformanceService {
  private static final int ROLLING_WINDOW_DAYS = 90;

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
    LocalDate windowStart = asOfDate.minusDays(ROLLING_WINDOW_DAYS - 1L);
    SpendingBucketPerformanceProjection aggregate =
        transactions.aggregateRollingPerformance(ownerId, windowStart, asOfDate);
    return new RollingSpendingBucketPerformanceResponse(
        asOfDate, windowStart, asOfDate, value(aggregate.getPaycheckCount()), toSummary(aggregate));
  }

  private SpendingBucketPerformanceSummaryResponse toSummary(
      SpendingBucketPerformanceProjection aggregate) {
    if (value(aggregate.getBucketCount()) == 0) {
      return null;
    }
    long budgeted = value(aggregate.getBudgetedMinor());
    long spent = value(aggregate.getSpentMinor());
    return new SpendingBucketPerformanceSummaryResponse(budgeted, spent, budgeted - spent);
  }

  private long value(Long value) {
    return value == null ? 0 : value;
  }
}
