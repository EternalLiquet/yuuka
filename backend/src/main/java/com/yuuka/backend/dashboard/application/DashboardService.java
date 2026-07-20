package com.yuuka.backend.dashboard.application;

import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.bucket.domain.BucketCalculator;
import com.yuuka.backend.bucket.domain.BucketMetrics;
import com.yuuka.backend.bucket.infrastructure.BucketTransactionTotalProjection;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.dashboard.api.dto.DashboardActiveSummaryResponse;
import com.yuuka.backend.dashboard.api.dto.DashboardAttentionItemResponse;
import com.yuuka.backend.dashboard.api.dto.DashboardAttentionKind;
import com.yuuka.backend.dashboard.api.dto.DashboardExpenseListSummaryResponse;
import com.yuuka.backend.dashboard.api.dto.DashboardPaybackSummaryResponse;
import com.yuuka.backend.dashboard.api.dto.DashboardPaycheckPreviewResponse;
import com.yuuka.backend.dashboard.api.dto.DashboardPlannedSavingsSummaryResponse;
import com.yuuka.backend.dashboard.api.dto.DashboardSummaryResponse;
import com.yuuka.backend.expense.domain.ExpenseLedger;
import com.yuuka.backend.expense.domain.ExpenseLedgerState;
import com.yuuka.backend.expense.infrastructure.ExpenseLedgerTotalsProjection;
import com.yuuka.backend.expense.infrastructure.JpaExpenseLedgerItemRepository;
import com.yuuka.backend.expense.infrastructure.JpaExpenseLedgerRepository;
import com.yuuka.backend.payback.api.dto.PaybackSummaryResponse;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryStatusEvent;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckCalculator;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.infrastructure.JpaEntryStatusEventRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.paycheck.infrastructure.PaycheckMetricsProjection;
import com.yuuka.backend.sinkingfund.api.dto.SinkingFundSummaryResponse;
import com.yuuka.backend.sinkingfund.application.SinkingFundService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
  private static final int ATTENTION_LIMIT = 5;
  private static final int PREVIEW_LIMIT = 2;

  private final JpaPaycheckRepository paychecks;
  private final JpaPaycheckEntryRepository entries;
  private final JpaEntryStatusEventRepository statusEvents;
  private final JpaBucketTransactionRepository bucketTransactions;
  private final JpaExpenseLedgerRepository expenseLedgers;
  private final JpaExpenseLedgerItemRepository expenseItems;
  private final PaycheckCalculator paycheckCalculator;
  private final BucketCalculator bucketCalculator;
  private final PaybackService paybackService;
  private final SinkingFundService sinkingFundService;
  private final OwnerLocalDateService ownerLocalDateService;
  private final Clock clock;

  public DashboardService(
      JpaPaycheckRepository paychecks,
      JpaPaycheckEntryRepository entries,
      JpaEntryStatusEventRepository statusEvents,
      JpaBucketTransactionRepository bucketTransactions,
      JpaExpenseLedgerRepository expenseLedgers,
      JpaExpenseLedgerItemRepository expenseItems,
      PaycheckCalculator paycheckCalculator,
      BucketCalculator bucketCalculator,
      PaybackService paybackService,
      SinkingFundService sinkingFundService,
      OwnerLocalDateService ownerLocalDateService,
      Clock clock) {
    this.paychecks = paychecks;
    this.entries = entries;
    this.statusEvents = statusEvents;
    this.bucketTransactions = bucketTransactions;
    this.expenseLedgers = expenseLedgers;
    this.expenseItems = expenseItems;
    this.paycheckCalculator = paycheckCalculator;
    this.bucketCalculator = bucketCalculator;
    this.paybackService = paybackService;
    this.sinkingFundService = sinkingFundService;
    this.ownerLocalDateService = ownerLocalDateService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public DashboardSummaryResponse summary(UUID ownerId) {
    ZoneId ownerZone = ownerLocalDateService.zoneId(ownerId);
    LocalDate today = LocalDate.ofInstant(clock.instant(), ownerZone);
    List<Paycheck> activePaychecks =
        paychecks.findActivePage(ownerId, Pageable.unpaged()).getContent();
    List<UUID> paycheckIds = activePaychecks.stream().map(Paycheck::getId).toList();
    List<PaycheckEntry> liveEntries =
        paycheckIds.isEmpty() ? List.of() : entries.findAllLiveByPaycheckIds(ownerId, paycheckIds);
    Map<UUID, PaycheckMetrics> metrics = metrics(ownerId, activePaychecks, paycheckIds);

    List<ExpenseLedger> finalizedLedgers =
        expenseLedgers.findAllByOwnerIdAndStateAndDeletedAtIsNullOrderByFinalizedAtAscIdAsc(
            ownerId, ExpenseLedgerState.FINALIZED);
    Map<UUID, ExpenseLedgerTotalsProjection> expenseTotals =
        expenseTotals(ownerId, finalizedLedgers);

    PaybackSummaryResponse paybackSummary = paybackService.list(ownerId).summary();
    SinkingFundSummaryResponse plannedSavingsSummary =
        sinkingFundService.list(ownerId, false).summary();

    return new DashboardSummaryResponse(
        today,
        attentionItems(
            ownerId,
            today,
            ownerZone,
            activePaychecks,
            liveEntries,
            metrics,
            finalizedLedgers,
            expenseTotals),
        activeSummary(activePaychecks, metrics),
        new DashboardPaybackSummaryResponse(
            paybackSummary.totalRemainingMinor(), paybackSummary.activeCount()),
        new DashboardPlannedSavingsSummaryResponse(
            plannedSavingsSummary.totalActiveBalanceMinor(), plannedSavingsSummary.activeCount()),
        new DashboardExpenseListSummaryResponse(
            expenseLedgers.countByOwnerIdAndStateAndDeletedAtIsNull(
                ownerId, ExpenseLedgerState.OPEN),
            finalizedLedgers.size()));
  }

  private DashboardActiveSummaryResponse activeSummary(
      List<Paycheck> activePaychecks, Map<UUID, PaycheckMetrics> metrics) {
    List<DashboardPaycheckPreviewResponse> allPreviews =
        activePaychecks.stream()
            .map(
                paycheck -> {
                  PaycheckMetrics paycheckMetrics = metrics.get(paycheck.getId());
                  return new DashboardPaycheckPreviewResponse(
                      paycheck.getId(),
                      paycheck.getName(),
                      paycheck.getIncomeDate(),
                      paycheck.getAmountMinor(),
                      paycheckMetrics.unallocatedMinor(),
                      paycheckMetrics.notPaidCount(),
                      paycheckMetrics.processingCount());
                })
            .sorted(
                Comparator.comparing(
                        (DashboardPaycheckPreviewResponse preview) ->
                            preview.unallocatedMinor() <= 0)
                    .thenComparing(
                        Comparator.comparingLong(
                                (DashboardPaycheckPreviewResponse preview) ->
                                    preview.notPaidCount() + preview.processingCount())
                            .reversed())
                    .thenComparing(
                        DashboardPaycheckPreviewResponse::incomeDate, Comparator.reverseOrder())
                    .thenComparing(DashboardPaycheckPreviewResponse::paycheckId))
            .toList();
    return new DashboardActiveSummaryResponse(
        activePaychecks.size(),
        MoneyArithmetic.sum(metrics.values().stream().mapToLong(PaycheckMetrics::unallocatedMinor)),
        metrics.values().stream().mapToLong(PaycheckMetrics::notPaidCount).sum(),
        metrics.values().stream().mapToLong(PaycheckMetrics::processingCount).sum(),
        allPreviews.stream().limit(PREVIEW_LIMIT).toList());
  }

  private List<DashboardAttentionItemResponse> attentionItems(
      UUID ownerId,
      LocalDate today,
      ZoneId ownerZone,
      List<Paycheck> activePaychecks,
      List<PaycheckEntry> liveEntries,
      Map<UUID, PaycheckMetrics> metrics,
      List<ExpenseLedger> finalizedLedgers,
      Map<UUID, ExpenseLedgerTotalsProjection> expenseTotals) {
    List<AttentionCandidate> candidates = new ArrayList<>();
    for (Paycheck paycheck : activePaychecks) {
      PaycheckMetrics paycheckMetrics = metrics.get(paycheck.getId());
      if (paycheckMetrics.unallocatedMinor() > 0) {
        candidates.add(
            new AttentionCandidate(
                1,
                paycheck.getIncomeDate(),
                paycheckMetrics.unallocatedMinor(),
                paycheck.getId(),
                new DashboardAttentionItemResponse(
                    DashboardAttentionKind.UNALLOCATED_PAYCHECK,
                    paycheck.getId(),
                    null,
                    null,
                    paycheck.getName(),
                    paycheckMetrics.unallocatedMinor(),
                    null,
                    null)));
      }
    }

    Map<UUID, Long> spentByBucket = spentByBucket(ownerId, liveEntries);
    List<PaycheckEntry> processingEntries = new ArrayList<>();
    for (PaycheckEntry entry : liveEntries) {
      if (entry.getEntryType() == EntryType.BILL
          && entry.getPaymentMethod() == EntryPaymentMethod.MANUAL
          && entry.getStatus() == EntryStatus.NOT_PAID) {
        boolean pastDue = entry.getDueDate() != null && entry.getDueDate().isBefore(today);
        candidates.add(
            new AttentionCandidate(
                pastDue ? 0 : 5,
                entry.getDueDate(),
                entry.getAmountMinor(),
                entry.getId(),
                entryItem(
                    DashboardAttentionKind.MANUAL_BILL_NOT_PAID,
                    entry,
                    entry.getAmountMinor(),
                    entry.getDueDate(),
                    null)));
      }
      if (entry.getStatus() == EntryStatus.PROCESSING) {
        processingEntries.add(entry);
      }
      if (entry.getEntryType() == EntryType.SPENDING_BUCKET) {
        BucketMetrics bucket =
            bucketCalculator.calculate(
                entry.getAmountMinor(), List.of(spentByBucket.getOrDefault(entry.getId(), 0L)));
        if (bucket.overBudget()) {
          long overage = Math.abs(bucket.remainingMinor());
          candidates.add(
              new AttentionCandidate(
                  3,
                  null,
                  overage,
                  entry.getId(),
                  entryItem(
                      DashboardAttentionKind.OVER_BUDGET_BUCKET, entry, overage, null, null)));
        }
      }
    }

    addProcessingCandidates(ownerId, today, ownerZone, processingEntries, candidates);
    for (ExpenseLedger ledger : finalizedLedgers) {
      ExpenseLedgerTotalsProjection total = expenseTotals.get(ledger.getId());
      long totalMinor = total == null ? 0 : MoneyArithmetic.toLongExact(total.getTotalMinor());
      LocalDate finalizedDate = LocalDate.ofInstant(ledger.getFinalizedAt(), ownerZone);
      candidates.add(
          new AttentionCandidate(
              4,
              finalizedDate,
              totalMinor,
              ledger.getId(),
              new DashboardAttentionItemResponse(
                  DashboardAttentionKind.FINALIZED_EXPENSE_LEDGER,
                  null,
                  null,
                  ledger.getId(),
                  ledger.getName(),
                  totalMinor,
                  null,
                  finalizedDate)));
    }

    candidates.sort(this::compareCandidates);
    return candidates.stream().limit(ATTENTION_LIMIT).map(AttentionCandidate::response).toList();
  }

  private void addProcessingCandidates(
      UUID ownerId,
      LocalDate today,
      ZoneId ownerZone,
      List<PaycheckEntry> processingEntries,
      List<AttentionCandidate> candidates) {
    if (processingEntries.isEmpty()) {
      return;
    }
    Map<UUID, EntryStatusEvent> latestEvents =
        statusEvents
            .findLatestByOwnerIdAndEntryIds(
                ownerId, processingEntries.stream().map(PaycheckEntry::getId).toList())
            .stream()
            .collect(Collectors.toMap(EntryStatusEvent::getEntryId, Function.identity()));
    for (PaycheckEntry entry : processingEntries) {
      EntryStatusEvent event = latestEvents.get(entry.getId());
      if (event == null || event.getToStatus() != EntryStatus.PROCESSING) {
        continue;
      }
      LocalDate processingSince = LocalDate.ofInstant(event.getEffectiveAt(), ownerZone);
      if (processingSince.isAfter(today.minusDays(3))) {
        continue;
      }
      candidates.add(
          new AttentionCandidate(
              2,
              processingSince,
              entry.getAmountMinor(),
              entry.getId(),
              entryItem(
                  DashboardAttentionKind.PROCESSING_ENTRY,
                  entry,
                  entry.getAmountMinor(),
                  null,
                  processingSince)));
    }
  }

  private DashboardAttentionItemResponse entryItem(
      DashboardAttentionKind kind,
      PaycheckEntry entry,
      long amountMinor,
      LocalDate dueDate,
      LocalDate attentionSinceDate) {
    return new DashboardAttentionItemResponse(
        kind,
        entry.getPaycheckId(),
        entry.getId(),
        null,
        entry.getName(),
        amountMinor,
        dueDate,
        attentionSinceDate);
  }

  private int compareCandidates(AttentionCandidate left, AttentionCandidate right) {
    int rank = Integer.compare(left.rank(), right.rank());
    if (rank != 0) {
      return rank;
    }
    int relevant;
    if (left.rank() == 1 || left.rank() == 3) {
      relevant = Long.compare(right.amountMinor(), left.amountMinor());
      if (relevant == 0 && left.rank() == 1) {
        relevant = compareDates(right.date(), left.date());
      }
    } else {
      relevant = compareDates(left.date(), right.date());
      if (relevant == 0) {
        relevant = Long.compare(right.amountMinor(), left.amountMinor());
      }
    }
    return relevant != 0 ? relevant : left.stableId().compareTo(right.stableId());
  }

  private int compareDates(LocalDate left, LocalDate right) {
    if (left == null) return right == null ? 0 : 1;
    if (right == null) return -1;
    return left.compareTo(right);
  }

  private Map<UUID, PaycheckMetrics> metrics(
      UUID ownerId, List<Paycheck> activePaychecks, Collection<UUID> paycheckIds) {
    Map<UUID, Paycheck> byId =
        activePaychecks.stream().collect(Collectors.toMap(Paycheck::getId, Function.identity()));
    Map<UUID, PaycheckMetrics> result = new HashMap<>();
    if (!paycheckIds.isEmpty()) {
      for (PaycheckMetricsProjection projection :
          entries.aggregateMetricsByPaycheckIds(ownerId, paycheckIds)) {
        Paycheck paycheck = byId.get(projection.getPaycheckId());
        result.put(
            paycheck.getId(),
            paycheckCalculator.calculateFromTotals(
                paycheck.getAmountMinor(),
                value(projection.getAllocatedMinor()),
                value(projection.getPostedMinor()),
                value(projection.getProcessingMinor()),
                value(projection.getNotPaidMinor()),
                value(projection.getPostedCount()),
                value(projection.getProcessingCount()),
                value(projection.getNotPaidCount())));
      }
    }
    activePaychecks.forEach(
        paycheck ->
            result.putIfAbsent(
                paycheck.getId(),
                paycheckCalculator.calculate(paycheck.getAmountMinor(), List.of())));
    return result;
  }

  private Map<UUID, Long> spentByBucket(UUID ownerId, List<PaycheckEntry> liveEntries) {
    List<UUID> bucketIds =
        liveEntries.stream()
            .filter(entry -> entry.getEntryType() == EntryType.SPENDING_BUCKET)
            .map(PaycheckEntry::getId)
            .toList();
    if (bucketIds.isEmpty()) {
      return Map.of();
    }
    return bucketTransactions.aggregateSpentByEntryIds(ownerId, bucketIds).stream()
        .collect(
            Collectors.toMap(
                BucketTransactionTotalProjection::getEntryId,
                projection -> value(projection.getSpentMinor())));
  }

  private Map<UUID, ExpenseLedgerTotalsProjection> expenseTotals(
      UUID ownerId, List<ExpenseLedger> finalizedLedgers) {
    if (finalizedLedgers.isEmpty()) {
      return Map.of();
    }
    return expenseItems
        .totalsByLedgerIds(ownerId, finalizedLedgers.stream().map(ExpenseLedger::getId).toList())
        .stream()
        .collect(Collectors.toMap(ExpenseLedgerTotalsProjection::getLedgerId, Function.identity()));
  }

  private long value(Long value) {
    return value == null ? 0 : value;
  }

  private record AttentionCandidate(
      int rank,
      LocalDate date,
      long amountMinor,
      UUID stableId,
      DashboardAttentionItemResponse response) {}
}
