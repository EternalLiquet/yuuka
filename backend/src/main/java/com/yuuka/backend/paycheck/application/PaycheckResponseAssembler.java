package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.bucket.api.dto.SpendingBucketPerformanceSummaryResponse;
import com.yuuka.backend.bucket.application.SpendingBucketPerformanceService;
import com.yuuka.backend.bucket.domain.BucketCalculator;
import com.yuuka.backend.bucket.domain.BucketMetrics;
import com.yuuka.backend.bucket.domain.BucketTransaction;
import com.yuuka.backend.bucket.infrastructure.BucketTransactionTotalProjection;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.domain.AllocationLine;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckCalculator;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.PaycheckMetricsProjection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class PaycheckResponseAssembler {
  private final JpaPaycheckEntryRepository entries;
  private final JpaBucketTransactionRepository bucketTransactions;
  private final PaycheckCalculator paycheckCalculator;
  private final BucketCalculator bucketCalculator;
  private final SpendingBucketPerformanceService spendingBucketPerformanceService;
  private final OwnerLocalDateService ownerLocalDateService;

  PaycheckResponseAssembler(
      JpaPaycheckEntryRepository entries,
      JpaBucketTransactionRepository bucketTransactions,
      PaycheckCalculator paycheckCalculator,
      BucketCalculator bucketCalculator,
      SpendingBucketPerformanceService spendingBucketPerformanceService,
      OwnerLocalDateService ownerLocalDateService) {
    this.entries = entries;
    this.bucketTransactions = bucketTransactions;
    this.paycheckCalculator = paycheckCalculator;
    this.bucketCalculator = bucketCalculator;
    this.spendingBucketPerformanceService = spendingBucketPerformanceService;
    this.ownerLocalDateService = ownerLocalDateService;
  }

  PaycheckResponse toResponse(Paycheck paycheck, List<PaycheckEntry> liveEntries) {
    return toResponse(
        paycheck, liveEntries, ownerLocalDateService.currentDate(paycheck.getOwnerId()));
  }

  PaycheckResponse toResponse(
      Paycheck paycheck, List<PaycheckEntry> liveEntries, LocalDate asOfDate) {
    return toResponse(
        paycheck, liveEntries, asOfDate, liveEntries.stream().map(this::toEntryResponse).toList());
  }

  PaycheckResponse toResponse(
      Paycheck paycheck,
      List<PaycheckEntry> liveEntries,
      LocalDate asOfDate,
      List<EntryResponse> entries) {
    return PaycheckResponse.from(
        paycheck,
        calculate(paycheck, liveEntries),
        spendingBucketPerformanceService.paycheckSummary(
            paycheck.getOwnerId(), paycheck.getId(), asOfDate),
        entries);
  }

  List<PaycheckResponse> toResponses(UUID ownerId, List<Paycheck> paychecks, LocalDate asOfDate) {
    if (paychecks.isEmpty()) {
      return List.of();
    }
    List<UUID> paycheckIds = paychecks.stream().map(Paycheck::getId).toList();
    Map<UUID, Paycheck> paychecksById =
        paychecks.stream().collect(Collectors.toMap(Paycheck::getId, Function.identity()));
    List<PaycheckEntry> liveEntries = entries.findAllLiveByPaycheckIds(ownerId, paycheckIds);
    Map<UUID, List<PaycheckEntry>> entriesByPaycheck =
        liveEntries.stream()
            .collect(
                Collectors.groupingBy(
                    PaycheckEntry::getPaycheckId, LinkedHashMap::new, Collectors.toList()));
    Map<UUID, PaycheckMetrics> metricsByPaycheck =
        metricsByPaycheck(ownerId, paycheckIds, paychecksById);
    Map<UUID, SpendingBucketPerformanceSummaryResponse> performanceByPaycheck =
        spendingBucketPerformanceService.paycheckSummaries(ownerId, paycheckIds, asOfDate);
    Map<UUID, BucketMetrics> bucketMetricsByEntry = bucketMetricsByEntry(ownerId, liveEntries);

    return paychecks.stream()
        .map(
            paycheck -> {
              List<PaycheckEntry> paycheckEntries =
                  entriesByPaycheck.getOrDefault(paycheck.getId(), List.of());
              List<EntryResponse> entryResponses =
                  paycheckEntries.stream()
                      .map(entry -> toEntryResponse(entry, bucketMetricsByEntry))
                      .toList();
              return PaycheckResponse.from(
                  paycheck,
                  metricsByPaycheck.getOrDefault(
                      paycheck.getId(),
                      paycheckCalculator.calculate(paycheck.getAmountMinor(), List.of())),
                  performanceByPaycheck.get(paycheck.getId()),
                  entryResponses);
            })
        .toList();
  }

  EntryResponse toEntryResponse(PaycheckEntry entry) {
    if (entry.getEntryType() != EntryType.SPENDING_BUCKET) {
      return EntryResponse.from(entry, null, null, null);
    }
    List<Long> amounts =
        bucketTransactions
            .findAllByEntryIdAndOwnerIdAndDeletedAtIsNullOrderByEffectiveDateDescCreatedAtDesc(
                entry.getId(), entry.getOwnerId())
            .stream()
            .map(BucketTransaction::getAmountMinor)
            .toList();
    BucketMetrics metrics = bucketCalculator.calculate(entry.getAmountMinor(), amounts);
    return EntryResponse.from(
        entry, metrics.spentMinor(), metrics.remainingMinor(), metrics.overBudget());
  }

  private EntryResponse toEntryResponse(
      PaycheckEntry entry, Map<UUID, BucketMetrics> bucketMetricsByEntry) {
    if (entry.getEntryType() != EntryType.SPENDING_BUCKET) {
      return EntryResponse.from(entry, null, null, null);
    }
    BucketMetrics metrics =
        bucketMetricsByEntry.getOrDefault(
            entry.getId(), bucketCalculator.calculate(entry.getAmountMinor(), List.of()));
    return EntryResponse.from(
        entry, metrics.spentMinor(), metrics.remainingMinor(), metrics.overBudget());
  }

  private Map<UUID, PaycheckMetrics> metricsByPaycheck(
      UUID ownerId, Collection<UUID> paycheckIds, Map<UUID, Paycheck> paychecksById) {
    return entries.aggregateMetricsByPaycheckIds(ownerId, paycheckIds).stream()
        .collect(
            Collectors.toMap(
                PaycheckMetricsProjection::getPaycheckId,
                projection ->
                    metricsFromProjection(
                        paychecksById.get(projection.getPaycheckId()), projection)));
  }

  private PaycheckMetrics metricsFromProjection(
      Paycheck paycheck, PaycheckMetricsProjection projection) {
    return paycheckCalculator.calculateFromTotals(
        paycheck.getAmountMinor(),
        value(projection.getAllocatedMinor()),
        value(projection.getPostedMinor()),
        value(projection.getProcessingMinor()),
        value(projection.getNotPaidMinor()),
        value(projection.getPostedCount()),
        value(projection.getProcessingCount()),
        value(projection.getNotPaidCount()));
  }

  private Map<UUID, BucketMetrics> bucketMetricsByEntry(
      UUID ownerId, List<PaycheckEntry> liveEntries) {
    Map<UUID, PaycheckEntry> bucketEntriesById =
        liveEntries.stream()
            .filter(entry -> entry.getEntryType() == EntryType.SPENDING_BUCKET)
            .collect(Collectors.toMap(PaycheckEntry::getId, Function.identity()));
    if (bucketEntriesById.isEmpty()) {
      return Map.of();
    }
    return bucketTransactions.aggregateSpentByEntryIds(ownerId, bucketEntriesById.keySet()).stream()
        .collect(
            Collectors.toMap(
                BucketTransactionTotalProjection::getEntryId,
                projection -> {
                  PaycheckEntry entry = bucketEntriesById.get(projection.getEntryId());
                  return bucketCalculator.calculate(
                      entry.getAmountMinor(), List.of(value(projection.getSpentMinor())));
                }));
  }

  PaycheckMetrics calculate(Paycheck paycheck, List<PaycheckEntry> liveEntries) {
    return calculate(paycheck.getAmountMinor(), allocationLines(liveEntries));
  }

  PaycheckMetrics calculate(long amountMinor, List<AllocationLine> allocationLines) {
    return paycheckCalculator.calculate(amountMinor, allocationLines);
  }

  List<AllocationLine> allocationLines(List<PaycheckEntry> liveEntries) {
    return liveEntries.stream()
        .map(entry -> new AllocationLine(entry.getAmountMinor(), entry.getStatus(), false))
        .toList();
  }

  private long value(Long value) {
    return value == null ? 0 : value;
  }
}
