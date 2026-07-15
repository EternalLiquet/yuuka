package com.yuuka.backend.paycheck.api.dto;

import com.yuuka.backend.bucket.api.dto.SpendingBucketPerformanceSummaryResponse;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PaycheckResponse(
    UUID id,
    String name,
    String source,
    long amountMinor,
    LocalDate incomeDate,
    PaycheckState state,
    UUID templateSourceId,
    String notes,
    long allocatedMinor,
    long unallocatedMinor,
    BigDecimal allocationPercent,
    long postedMinor,
    long processingMinor,
    long notPaidMinor,
    BigDecimal completionPercent,
    long postedCount,
    long processingCount,
    long notPaidCount,
    boolean requiresAttention,
    SpendingBucketPerformanceSummaryResponse spendingBucketPerformance,
    List<EntryResponse> entries,
    Instant createdAt,
    Instant updatedAt,
    Instant closedAt,
    Instant reopenedAt,
    Instant archivedAt,
    long version) {
  public static PaycheckResponse from(
      Paycheck paycheck,
      PaycheckMetrics metrics,
      SpendingBucketPerformanceSummaryResponse spendingBucketPerformance,
      List<EntryResponse> entries) {
    return new PaycheckResponse(
        paycheck.getId(),
        paycheck.getName(),
        paycheck.getSource(),
        paycheck.getAmountMinor(),
        paycheck.getIncomeDate(),
        paycheck.getState(),
        paycheck.getTemplateSourceId(),
        paycheck.getNotes(),
        metrics.allocatedMinor(),
        metrics.unallocatedMinor(),
        metrics.allocationPercent(),
        metrics.postedMinor(),
        metrics.processingMinor(),
        metrics.notPaidMinor(),
        metrics.completionPercent(),
        metrics.postedCount(),
        metrics.processingCount(),
        metrics.notPaidCount(),
        metrics.requiresAttention(),
        spendingBucketPerformance,
        List.copyOf(entries),
        paycheck.getCreatedAt(),
        paycheck.getUpdatedAt(),
        paycheck.getClosedAt(),
        paycheck.getReopenedAt(),
        paycheck.getArchivedAt(),
        paycheck.getVersion());
  }
}
