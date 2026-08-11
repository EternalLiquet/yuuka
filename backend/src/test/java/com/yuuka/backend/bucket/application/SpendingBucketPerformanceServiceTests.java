package com.yuuka.backend.bucket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuuka.backend.bucket.api.dto.SpendingBucketInsightsResponse;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.bucket.infrastructure.SpendingBucketInsightProjection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpendingBucketPerformanceServiceTests {
  private final JpaBucketTransactionRepository transactions =
      mock(JpaBucketTransactionRepository.class);
  private final SpendingBucketPerformanceService service =
      new SpendingBucketPerformanceService(transactions);

  @Test
  void aggregatesOverallRowsAndKeepsExactNameSelectionSparse() {
    UUID ownerId = UUID.randomUUID();
    LocalDate asOfDate = LocalDate.of(2026, 7, 15);
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    List<SpendingBucketInsightProjection> rows =
        List.of(
            row(firstId, "First", "2026-07-01", "food", "Food", 1, 1000, 800),
            row(firstId, "First", "2026-07-01", "gas", "Gas", 2, 500, 700),
            row(secondId, "Second", "2026-07-08", "food", "Food", 1, 1200, 1200));
    when(transactions.findRecentInsightRows(ownerId, asOfDate, 12)).thenReturn(rows);

    SpendingBucketInsightsResponse overall = service.insights(ownerId, asOfDate, null);
    assertThat(overall.scope()).isEqualTo("ALL");
    assertThat(overall.qualifyingPaycheckCount()).isEqualTo(2);
    assertThat(overall.availableBucketNames()).containsExactly("Food", "Gas");
    assertThat(overall.points()).hasSize(2);
    assertThat(overall.points().getFirst().matchingBucketCount()).isEqualTo(3);
    assertThat(overall.points().getFirst().budgetedMinor()).isEqualTo(1500);
    assertThat(overall.points().getFirst().spentMinor()).isEqualTo(1500);
    assertThat(overall.points().getFirst().netMinor()).isZero();

    SpendingBucketInsightsResponse selected = service.insights(ownerId, asOfDate, " FOOD ");
    assertThat(selected.scope()).isEqualTo("BUCKET_NAME");
    assertThat(selected.selectedBucketName()).isEqualTo("Food");
    assertThat(selected.points()).hasSize(2);
    assertThat(selected.points().getFirst().matchingBucketCount()).isEqualTo(1);
    assertThat(selected.points().getFirst().netMinor()).isEqualTo(200);
    verify(transactions, times(2)).findRecentInsightRows(ownerId, asOfDate, 12);
  }

  private SpendingBucketInsightProjection row(
      UUID paycheckId,
      String paycheckName,
      String incomeDate,
      String normalizedName,
      String displayName,
      long matchingCount,
      long budgeted,
      long spent) {
    SpendingBucketInsightProjection row = mock(SpendingBucketInsightProjection.class);
    when(row.getPaycheckId()).thenReturn(paycheckId);
    when(row.getPaycheckName()).thenReturn(paycheckName);
    when(row.getIncomeDate()).thenReturn(LocalDate.parse(incomeDate));
    when(row.getNormalizedName()).thenReturn(normalizedName);
    when(row.getDisplayName()).thenReturn(displayName);
    when(row.getMatchingBucketCount()).thenReturn(matchingCount);
    when(row.getBudgetedMinor()).thenReturn(BigDecimal.valueOf(budgeted));
    when(row.getSpentMinor()).thenReturn(BigDecimal.valueOf(spent));
    return row;
  }
}
