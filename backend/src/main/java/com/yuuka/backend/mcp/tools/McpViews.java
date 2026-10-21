package com.yuuka.backend.mcp.tools;

import com.yuuka.backend.dashboard.api.dto.DashboardAttentionItemResponse;
import com.yuuka.backend.dashboard.api.dto.DashboardAttentionKind;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportSummaryResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillOccurrenceResponse;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import com.yuuka.backend.search.api.dto.EntrySearchResultResponse;
import com.yuuka.backend.search.domain.PaycheckContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MCP-specific data views. Money, counts-as-totals and optimistic-lock versions are decimal
 * strings; free-text notes and owner profile fields are intentionally absent.
 */
public final class McpViews {
  private McpViews() {}

  public record PaycheckSummary(
      UUID id,
      String name,
      String source,
      String amount,
      LocalDate incomeDate,
      PaycheckState state,
      String allocated,
      String unallocated,
      String posted,
      String processing,
      String notPaid,
      BigDecimal allocationPercent,
      BigDecimal completionPercent,
      long notPaidCount,
      long processingCount,
      long postedCount,
      int entryCount,
      boolean requiresAttention,
      UUID templateSourceId,
      Instant createdAt,
      Instant updatedAt,
      Instant closedAt,
      Instant reopenedAt,
      Instant archivedAt,
      String version) {
    public static PaycheckSummary from(PaycheckResponse p) {
      return new PaycheckSummary(
          p.id(),
          p.name(),
          p.source(),
          McpValues.money(p.amountMinor()),
          p.incomeDate(),
          p.state(),
          McpValues.money(p.allocatedMinor()),
          McpValues.money(p.unallocatedMinor()),
          McpValues.money(p.postedMinor()),
          McpValues.money(p.processingMinor()),
          McpValues.money(p.notPaidMinor()),
          p.allocationPercent(),
          p.completionPercent(),
          p.notPaidCount(),
          p.processingCount(),
          p.postedCount(),
          p.entries().size(),
          p.requiresAttention(),
          p.templateSourceId(),
          p.createdAt(),
          p.updatedAt(),
          p.closedAt(),
          p.reopenedAt(),
          p.archivedAt(),
          McpValues.version(p.version()));
    }
  }

  public record PaycheckList(List<PaycheckSummary> items) {}

  public record EntryView(
      UUID id,
      EntryType entryType,
      EntryPaymentMethod paymentMethod,
      String name,
      String amount,
      boolean amountEstimated,
      EntryStatus status,
      int position,
      LocalDate dueDate,
      String accountName,
      String payee,
      String target,
      LocalDate targetDate,
      UUID paybackId,
      UUID sinkingFundId,
      UUID sourceRecurringBillDefinitionId,
      LocalDate sourceRecurringOccurrenceDate,
      UUID sourceExpenseLedgerId,
      String spent,
      String remaining,
      Boolean overBudget,
      Instant createdAt,
      Instant updatedAt,
      String version) {
    public static EntryView from(EntryResponse e) {
      return new EntryView(
          e.id(),
          e.entryType(),
          e.paymentMethod(),
          e.name(),
          McpValues.money(e.amountMinor()),
          e.amountEstimated(),
          e.status(),
          e.position(),
          e.dueDate(),
          e.accountName(),
          e.payee(),
          McpValues.money(e.targetMinor()),
          e.targetDate(),
          e.paybackId(),
          e.sinkingFundId(),
          e.sourceRecurringBillDefinitionId(),
          e.sourceRecurringOccurrenceDate(),
          e.sourceExpenseLedgerId(),
          McpValues.money(e.spentMinor()),
          McpValues.money(e.remainingMinor()),
          e.overBudget(),
          e.createdAt(),
          e.updatedAt(),
          McpValues.version(e.version()));
    }
  }

  public record PaycheckDetail(PaycheckSummary paycheck, List<EntryView> entries) {
    public static PaycheckDetail from(PaycheckResponse p) {
      return new PaycheckDetail(
          PaycheckSummary.from(p), p.entries().stream().map(EntryView::from).toList());
    }
  }

  public record AttentionItem(
      DashboardAttentionKind kind,
      UUID paycheckId,
      UUID entryId,
      UUID expenseLedgerId,
      String name,
      String amount,
      LocalDate dueDate,
      LocalDate attentionSinceDate) {
    public static AttentionItem from(DashboardAttentionItemResponse i) {
      return new AttentionItem(
          i.kind(),
          i.paycheckId(),
          i.entryId(),
          i.expenseLedgerId(),
          i.name(),
          McpValues.money(i.amountMinor()),
          i.dueDate(),
          i.attentionSinceDate());
    }
  }

  public record DashboardView(
      long activePaycheckCount,
      String totalUnallocated,
      long notPaidEntryCount,
      long processingEntryCount,
      List<AttentionItem> needsAttention,
      int needsAttentionLimit,
      boolean needsAttentionMayBeTruncated) {}

  public record BudgetContext(
      String currency,
      String timeZone,
      LocalDate ownerLocalDate,
      String backendVersion,
      boolean bankFeed,
      boolean writesEnabled,
      DashboardView dashboard) {}

  public record LinkedEntry(
      UUID entryId, UUID paycheckId, String paycheckName, EntryStatus status) {
    public static LinkedEntry from(RecurringBillImportSummaryResponse i) {
      return new LinkedEntry(i.entryId(), i.paycheckId(), i.paycheckName(), i.status());
    }
  }

  public record RecurringOccurrence(
      UUID definitionId,
      String definitionVersion,
      LocalDate occurrenceDate,
      String name,
      RecurringBillAmountMode amountMode,
      String typicalAmount,
      String planningAmount,
      String amount,
      boolean amountEntered,
      String occurrenceAmountVersion,
      EntryPaymentMethod paymentMethod,
      String accountName,
      String payee,
      String linkStatus,
      int linkedEntryCount,
      List<LinkedEntry> linkedEntries) {
    public static RecurringOccurrence from(RecurringBillOccurrenceResponse o) {
      return new RecurringOccurrence(
          o.definitionId(),
          McpValues.version(o.definitionVersion()),
          o.occurrenceDate(),
          o.name(),
          o.amountMode(),
          McpValues.money(o.typicalAmountMinor()),
          McpValues.money(o.planningAmountMinor()),
          McpValues.money(o.amountMinor()),
          o.amountEntered(),
          McpValues.money(o.occurrenceAmountVersion()),
          o.paymentMethod(),
          o.accountName(),
          o.payee(),
          o.imports().isEmpty() ? "UNLINKED" : "LINKED",
          o.imports().size(),
          o.imports().stream().map(LinkedEntry::from).toList());
    }
  }

  public record RecurringCoverage(
      LocalDate from, LocalDate through, List<RecurringOccurrence> items) {}

  public record SearchCandidate(
      String matchKind,
      UUID entryId,
      UUID paycheckId,
      String entryName,
      String amount,
      EntryType entryType,
      EntryPaymentMethod paymentMethod,
      EntryStatus status,
      String paycheckName,
      LocalDate paycheckIncomeDate,
      PaycheckContext paycheckContext) {
    public static SearchCandidate from(EntrySearchResultResponse r) {
      return new SearchCandidate(
          "CANDIDATE",
          r.entryId(),
          r.paycheckId(),
          r.entryName(),
          McpValues.money(r.amountMinor()),
          r.entryType(),
          r.paymentMethod(),
          r.status(),
          r.paycheckName(),
          r.paycheckIncomeDate(),
          r.paycheckContext());
    }
  }

  public record SearchResults(List<SearchCandidate> items) {}
}
