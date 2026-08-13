package com.yuuka.backend.bucket.application;

import com.yuuka.backend.bucket.api.dto.RollingSpendingBucketPerformanceResponse;
import com.yuuka.backend.bucket.api.dto.SpendingBucketInsightPointResponse;
import com.yuuka.backend.bucket.api.dto.SpendingBucketInsightsResponse;
import com.yuuka.backend.bucket.api.dto.SpendingBucketPerformanceSummaryResponse;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.bucket.infrastructure.PaycheckSpendingBucketPerformanceProjection;
import com.yuuka.backend.bucket.infrastructure.SpendingBucketInsightProjection;
import com.yuuka.backend.bucket.infrastructure.SpendingBucketPerformanceProjection;
import com.yuuka.backend.common.api.MoneyArithmetic;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpendingBucketPerformanceService {
  public static final int THIRTY_DAY_WINDOW = 30;
  public static final int NINETY_DAY_WINDOW = 90;
  public static final int INSIGHTS_PAYCHECK_LIMIT = 12;

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
  public Map<UUID, SpendingBucketPerformanceSummaryResponse> paycheckSummaries(
      UUID ownerId, Collection<UUID> paycheckIds, LocalDate asOfDate) {
    if (paycheckIds.isEmpty()) {
      return Map.of();
    }
    return transactions
        .aggregatePaycheckPerformanceByPaycheckIds(ownerId, paycheckIds, asOfDate)
        .stream()
        .collect(
            Collectors.toMap(
                PaycheckSpendingBucketPerformanceProjection::getPaycheckId, this::toSummary));
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

  @Transactional(readOnly = true)
  public SpendingBucketInsightsResponse insights(
      UUID ownerId, LocalDate asOfDate, String bucketName) {
    List<SpendingBucketInsightProjection> rows =
        transactions.findRecentInsightRows(ownerId, asOfDate, INSIGHTS_PAYCHECK_LIMIT);
    String selectedNormalized = normalize(bucketName);
    LinkedHashMap<String, String> availableNames = new LinkedHashMap<>();
    LinkedHashMap<UUID, InsightAccumulator> points = new LinkedHashMap<>();
    for (SpendingBucketInsightProjection row : rows) {
      availableNames.putIfAbsent(row.getNormalizedName(), row.getDisplayName());
      if (selectedNormalized != null && !selectedNormalized.equals(row.getNormalizedName())) {
        continue;
      }
      InsightAccumulator point =
          points.computeIfAbsent(
              row.getPaycheckId(),
              ignored ->
                  new InsightAccumulator(
                      row.getPaycheckId(), row.getPaycheckName(), row.getIncomeDate()));
      point.add(
          value(row.getMatchingBucketCount()),
          MoneyArithmetic.toLongExact(row.getBudgetedMinor()),
          MoneyArithmetic.toLongExact(row.getSpentMinor()));
    }
    List<String> labels =
        availableNames.values().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
            .toList();
    String selectedLabel =
        selectedNormalized == null
            ? null
            : availableNames.getOrDefault(selectedNormalized, bucketName.trim());
    return new SpendingBucketInsightsResponse(
        asOfDate,
        INSIGHTS_PAYCHECK_LIMIT,
        (int) rows.stream().map(SpendingBucketInsightProjection::getPaycheckId).distinct().count(),
        selectedNormalized == null ? "ALL" : "BUCKET_NAME",
        selectedLabel,
        labels,
        points.values().stream().map(InsightAccumulator::response).toList());
  }

  private String normalize(String bucketName) {
    if (bucketName == null || bucketName.trim().isEmpty()) return null;
    return bucketName.trim().toLowerCase(Locale.ROOT);
  }

  private static final class InsightAccumulator {
    private final UUID paycheckId;
    private final String paycheckName;
    private final LocalDate incomeDate;
    private long matchingBucketCount;
    private long budgetedMinor;
    private long spentMinor;

    private InsightAccumulator(UUID paycheckId, String paycheckName, LocalDate incomeDate) {
      this.paycheckId = paycheckId;
      this.paycheckName = paycheckName;
      this.incomeDate = incomeDate;
    }

    private void add(long count, long budgeted, long spent) {
      matchingBucketCount = Math.addExact(matchingBucketCount, count);
      budgetedMinor = MoneyArithmetic.add(budgetedMinor, budgeted);
      spentMinor = MoneyArithmetic.add(spentMinor, spent);
    }

    private SpendingBucketInsightPointResponse response() {
      return new SpendingBucketInsightPointResponse(
          paycheckId,
          paycheckName,
          incomeDate,
          matchingBucketCount,
          budgetedMinor,
          spentMinor,
          MoneyArithmetic.subtract(budgetedMinor, spentMinor));
    }
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
