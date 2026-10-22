package com.yuuka.backend.mcp.tools;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import com.yuuka.backend.review.domain.ReviewAmountBasis;
import com.yuuka.backend.review.domain.ReviewCheck;
import com.yuuka.backend.review.domain.ReviewDefinitionState;
import com.yuuka.backend.review.domain.ReviewFinding;
import com.yuuka.backend.review.domain.ReviewMatchReason;
import com.yuuka.backend.review.domain.ReviewQueue;
import com.yuuka.backend.review.domain.ReviewScope;
import com.yuuka.backend.review.domain.ReviewSeverity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** MCP views of review findings: money and versions are decimal strings. */
public final class McpReviewViews {
  private McpReviewViews() {}

  public record Queue(
      LocalDate from,
      LocalDate through,
      String paycheckScope,
      Map<String, Integer> summary,
      List<Finding> findings) {
    public static Queue from(ReviewQueue queue) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (ReviewCheck check : ReviewCheck.values()) {
        counts.put(check.name(), Math.toIntExact(queue.summary().getOrDefault(check, 0L)));
      }
      return new Queue(
          queue.from(),
          queue.through(),
          ReviewScope.ALL_ACTIVE_PAYCHECKS.name(),
          counts,
          queue.findings().stream().map(Finding::from).toList());
    }
  }

  public record Finding(
      ReviewCheck checkCode,
      ReviewSeverity severity,
      ReviewScope scope,
      String question,
      LocalDate primaryDate,
      UUID paycheckId,
      UUID entryId,
      UUID definitionId,
      Evidence evidence) {
    static Finding from(ReviewFinding f) {
      return new Finding(
          f.check(),
          f.severity(),
          f.scope(),
          f.question(),
          f.primaryDate(),
          f.paycheckId(),
          f.entryId(),
          f.definitionId(),
          Evidence.from(f.evidence()));
    }
  }

  public record Evidence(
      Definition definition,
      Occurrence occurrence,
      PaycheckFacts paycheck,
      EntryFacts entry,
      List<EntryFacts> linkedEntries,
      List<PossibleMatch> possibleMatches,
      CandidateSearch candidateSearch,
      Processing processing,
      Bucket bucket) {
    static Evidence from(ReviewFinding.Evidence e) {
      return new Evidence(
          e.definition() == null ? null : Definition.from(e.definition()),
          e.occurrence() == null ? null : Occurrence.from(e.occurrence()),
          e.paycheck() == null ? null : PaycheckFacts.from(e.paycheck()),
          e.entry() == null ? null : EntryFacts.from(e.entry()),
          e.linkedEntries().stream().map(EntryFacts::from).toList(),
          e.possibleMatches().stream().map(PossibleMatch::from).toList(),
          e.candidateSearch() == null ? null : CandidateSearch.from(e.candidateSearch()),
          e.processing() == null ? null : Processing.from(e.processing()),
          e.bucket() == null ? null : Bucket.from(e.bucket()));
    }
  }

  public record Definition(
      UUID id,
      String version,
      String name,
      RecurringBillAmountMode amountMode,
      String typicalAmount,
      String planningAmount,
      ReviewDefinitionState state) {
    static Definition from(ReviewFinding.DefinitionEvidence d) {
      return new Definition(
          d.id(),
          McpValues.version(d.version()),
          d.name(),
          d.amountMode(),
          McpValues.money(d.typicalAmountMinor()),
          McpValues.money(d.planningAmountMinor()),
          d.state());
    }
  }

  public record Occurrence(
      LocalDate date,
      String amount,
      boolean amountEntered,
      ReviewAmountBasis amountBasis,
      String occurrenceAmountVersion) {
    static Occurrence from(ReviewFinding.OccurrenceEvidence o) {
      return new Occurrence(
          o.date(),
          McpValues.money(o.amountMinor()),
          o.amountEntered(),
          o.amountBasis(),
          o.occurrenceAmountVersion() == null
              ? null
              : McpValues.version(o.occurrenceAmountVersion()));
    }
  }

  public record PaycheckFacts(
      UUID id,
      String name,
      String version,
      LocalDate incomeDate,
      PaycheckState state,
      String amount,
      String allocated,
      String unallocated) {
    static PaycheckFacts from(ReviewFinding.PaycheckEvidence p) {
      return new PaycheckFacts(
          p.id(),
          p.name(),
          McpValues.version(p.version()),
          p.incomeDate(),
          p.state(),
          McpValues.money(p.amountMinor()),
          McpValues.money(p.allocatedMinor()),
          McpValues.money(p.unallocatedMinor()));
    }
  }

  public record EntryFacts(
      UUID entryId,
      UUID paycheckId,
      String paycheckName,
      LocalDate paycheckIncomeDate,
      PaycheckState paycheckState,
      String name,
      EntryType entryType,
      EntryStatus status,
      String amount,
      String version,
      LocalDate dueDate,
      EntryPaymentMethod paymentMethod,
      boolean amountEstimated,
      ReviewAmountBasis amountBasis,
      Provenance provenance) {
    static EntryFacts from(ReviewFinding.EntryEvidence e) {
      return new EntryFacts(
          e.entryId(),
          e.paycheckId(),
          e.paycheckName(),
          e.paycheckIncomeDate(),
          e.paycheckState(),
          e.name(),
          e.entryType(),
          e.status(),
          McpValues.money(e.amountMinor()),
          McpValues.version(e.version()),
          e.dueDate(),
          e.paymentMethod(),
          e.amountEstimated(),
          e.amountBasis(),
          e.provenance() == null ? null : Provenance.from(e.provenance()));
    }
  }

  public record Provenance(
      UUID definitionId,
      ReviewDefinitionState definitionState,
      String currentDefinitionVersion,
      String currentDefinitionName,
      boolean definitionUpdatedAfterEntryCreated,
      LocalDate sourceOccurrenceDate) {
    static Provenance from(ReviewFinding.Provenance p) {
      return new Provenance(
          p.definitionId(),
          p.definitionState(),
          McpValues.version(p.currentDefinitionVersion()),
          p.currentDefinitionName(),
          p.definitionUpdatedAfterEntryCreated(),
          p.sourceOccurrenceDate());
    }
  }

  public record PossibleMatch(EntryFacts entry, List<ReviewMatchReason> reasons) {
    static PossibleMatch from(ReviewFinding.PossibleMatch m) {
      return new PossibleMatch(EntryFacts.from(m.entry()), m.reasons());
    }
  }

  public record CandidateSearch(String criteria, int limit, int returned, boolean complete) {
    static CandidateSearch from(ReviewFinding.CandidateSearch c) {
      return new CandidateSearch(c.criteria(), c.limit(), c.returned(), c.complete());
    }
  }

  public record Processing(
      Instant effectiveAt,
      Instant recordedAt,
      LocalDate processingSinceDate,
      LocalDate agedOnOrBeforeDate,
      long daysProcessing) {
    static Processing from(ReviewFinding.ProcessingEvidence p) {
      return new Processing(
          p.effectiveAt(),
          p.recordedAt(),
          p.processingSinceDate(),
          p.agedOnOrBeforeDate(),
          p.daysProcessing());
    }
  }

  public record Bucket(String budget, String spent, String remaining, String overage) {
    static Bucket from(ReviewFinding.BucketEvidence b) {
      return new Bucket(
          McpValues.money(b.budgetMinor()),
          McpValues.money(b.spentMinor()),
          McpValues.money(b.remainingMinor()),
          McpValues.money(b.overageMinor()));
    }
  }
}
