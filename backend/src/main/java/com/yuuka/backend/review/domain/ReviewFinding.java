package com.yuuka.backend.review.domain;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One derived bookkeeping question. Findings are never persisted, never acknowledged, and never
 * imply that any change should be made automatically. Money is signed 64-bit minor units.
 */
public record ReviewFinding(
    ReviewCheck check,
    ReviewSeverity severity,
    ReviewScope scope,
    String question,
    LocalDate primaryDate,
    UUID paycheckId,
    UUID entryId,
    UUID definitionId,
    Evidence evidence) {

  public record Evidence(
      DefinitionEvidence definition,
      OccurrenceEvidence occurrence,
      PaycheckEvidence paycheck,
      EntryEvidence entry,
      List<EntryEvidence> linkedEntries,
      List<PossibleMatch> possibleMatches,
      CandidateSearch candidateSearch,
      ProcessingEvidence processing,
      BucketEvidence bucket) {
    public Evidence {
      linkedEntries = List.copyOf(linkedEntries);
      possibleMatches = List.copyOf(possibleMatches);
    }
  }

  public record DefinitionEvidence(
      UUID id,
      long version,
      String name,
      RecurringBillAmountMode amountMode,
      Long typicalAmountMinor,
      Long planningAmountMinor,
      ReviewDefinitionState state) {}

  public record OccurrenceEvidence(
      LocalDate date,
      Long amountMinor,
      boolean amountEntered,
      ReviewAmountBasis amountBasis,
      Long occurrenceAmountVersion) {}

  public record PaycheckEvidence(
      UUID id,
      String name,
      long version,
      LocalDate incomeDate,
      PaycheckState state,
      long amountMinor,
      long allocatedMinor,
      long unallocatedMinor) {}

  public record EntryEvidence(
      UUID entryId,
      UUID paycheckId,
      String paycheckName,
      LocalDate paycheckIncomeDate,
      PaycheckState paycheckState,
      String name,
      EntryType entryType,
      EntryStatus status,
      long amountMinor,
      long version,
      LocalDate dueDate,
      EntryPaymentMethod paymentMethod,
      boolean amountEstimated,
      ReviewAmountBasis amountBasis,
      Provenance provenance) {}

  /**
   * Current state of the recurring definition an entry was imported from. Entries store no snapshot
   * of the definition at import time, so this is the definition as it is now.
   */
  public record Provenance(
      UUID definitionId,
      ReviewDefinitionState definitionState,
      long currentDefinitionVersion,
      String currentDefinitionName,
      boolean definitionUpdatedAfterEntryCreated,
      LocalDate sourceOccurrenceDate) {}

  public record PossibleMatch(EntryEvidence entry, List<ReviewMatchReason> reasons) {
    public PossibleMatch {
      reasons = List.copyOf(reasons);
    }
  }

  public record CandidateSearch(String criteria, int limit, int returned, boolean complete) {}

  public record ProcessingEvidence(
      Instant effectiveAt,
      Instant recordedAt,
      LocalDate processingSinceDate,
      LocalDate agedOnOrBeforeDate,
      long daysProcessing) {}

  public record BucketEvidence(
      long budgetMinor, long spentMinor, long remainingMinor, long overageMinor) {}
}
