package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.bucket.application.SpendingBucketPerformanceService;
import com.yuuka.backend.bucket.domain.BucketCalculator;
import com.yuuka.backend.bucket.domain.BucketMetrics;
import com.yuuka.backend.bucket.domain.BucketTransaction;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.domain.AllocationLine;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckCalculator;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class PaycheckResponseAssembler {
  private final JpaBucketTransactionRepository bucketTransactions;
  private final PaycheckCalculator paycheckCalculator;
  private final BucketCalculator bucketCalculator;
  private final SpendingBucketPerformanceService spendingBucketPerformanceService;
  private final OwnerLocalDateService ownerLocalDateService;

  PaycheckResponseAssembler(
      JpaBucketTransactionRepository bucketTransactions,
      PaycheckCalculator paycheckCalculator,
      BucketCalculator bucketCalculator,
      SpendingBucketPerformanceService spendingBucketPerformanceService,
      OwnerLocalDateService ownerLocalDateService) {
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
}
