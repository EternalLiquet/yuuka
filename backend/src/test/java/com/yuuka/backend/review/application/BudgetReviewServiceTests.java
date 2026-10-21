package com.yuuka.backend.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.review.domain.ReviewAmountBasis;
import com.yuuka.backend.review.domain.ReviewCheck;
import com.yuuka.backend.review.domain.ReviewDefinitionState;
import com.yuuka.backend.review.domain.ReviewFinding;
import com.yuuka.backend.review.domain.ReviewMatchReason;
import com.yuuka.backend.review.domain.ReviewQueue;
import com.yuuka.backend.review.domain.ReviewScope;
import com.yuuka.backend.review.domain.ReviewSeverity;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Derived review queue against PostgreSQL, without any MCP transport. */
class BudgetReviewServiceTests extends AbstractIntegrationTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 7, 19);
  private static final LocalDate JULY_START = LocalDate.of(2026, 7, 1);
  private static final LocalDate JULY_END = LocalDate.of(2026, 7, 31);

  @Autowired private BudgetReviewService review;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ProbeClock clock;

  @BeforeEach
  void resetClock() {
    // 2026-07-19 23:00 in America/Indianapolis (UTC-4).
    clock.setInstant(Instant.parse("2026-07-20T03:00:00Z"));
    clock.jdbc = jdbc;
    clock.probes.clear();
  }

  // ---- recurring occurrences
  // ---------------------------------------------------------------------

  @Test
  void unlinkedOccurrenceWithPlausibleManualBillIsAQuestionWithAnUnconfirmedMatch() {
    UUID owner = newOwner();
    UUID water = definition(owner, "Water", "FIXED", 4500L, 28, true, false, 0);
    UUID paycheck = paycheck(owner, "Pay", 100000, "2026-07-20", "ACTIVE", false);
    UUID typed = entry(owner, paycheck, "BILL", "water", 4500, "NOT_PAID", "AUTOPAY", "2026-07-27");
    entry(owner, paycheck, "BILL", "Gym", 3000, "NOT_PAID", "AUTOPAY", "2026-07-27");

    ReviewFinding finding =
        only(all(owner, JULY_START, JULY_END), ReviewCheck.RECURRING_OCCURRENCE_UNLINKED);

    assertThat(finding.severity()).isEqualTo(ReviewSeverity.MEDIUM);
    assertThat(finding.scope()).isEqualTo(ReviewScope.OCCURRENCE_WINDOW);
    assertThat(finding.primaryDate()).isEqualTo(LocalDate.of(2026, 7, 28));
    assertThat(finding.definitionId()).isEqualTo(water);
    assertThat(finding.question())
        .contains("not proof the bill is unpaid")
        .contains("not confirmed");
    assertThat(finding.question()).doesNotContainIgnoringCase("import");
    assertThat(finding.evidence().definition().state()).isEqualTo(ReviewDefinitionState.ACTIVE);
    assertThat(finding.evidence().definition().version()).isZero();
    assertThat(finding.evidence().occurrence().amountBasis()).isEqualTo(ReviewAmountBasis.FIXED);
    assertThat(finding.evidence().occurrence().amountMinor()).isEqualTo(4500L);
    assertThat(finding.evidence().possibleMatches()).hasSize(1);
    ReviewFinding.PossibleMatch match = finding.evidence().possibleMatches().get(0);
    assertThat(match.entry().entryId()).isEqualTo(typed);
    assertThat(match.entry().version()).isZero();
    assertThat(match.reasons())
        .containsExactly(
            ReviewMatchReason.NAME_EQUALS_IGNORING_CASE,
            ReviewMatchReason.AMOUNT_EQUALS_OCCURRENCE_OR_TYPICAL);
    assertThat(finding.evidence().candidateSearch().complete()).isTrue();
    assertThat(finding.evidence().candidateSearch().limit()).isEqualTo(10);
    assertThat(finding.evidence().candidateSearch().returned()).isEqualTo(1);
    assertThat(finding.evidence().linkedEntries()).isEmpty();
  }

  @Test
  void possibleMatchesRespectTheSevenDayBandOwnerAndSoftDeletion() {
    UUID owner = newOwner();
    UUID other = newOwner();
    definition(owner, "Water", "FIXED", 4500L, 28, true, false, 0);
    UUID near = paycheck(owner, "Near", 1000000, "2026-07-20", "ACTIVE", false);
    UUID far = paycheck(owner, "Far", 1000000, "2026-09-01", "ACTIVE", false);
    UUID deletedPaycheck = paycheck(owner, "Gone", 1000000, "2026-07-20", "ACTIVE", true);
    UUID otherPaycheck = paycheck(other, "Other", 1000000, "2026-07-20", "ACTIVE", false);
    UUID edgeIn = entry(owner, near, "BILL", "Water", 1, "NOT_PAID", "AUTOPAY", "2026-08-04");
    entry(owner, near, "BILL", "Water", 2, "NOT_PAID", "AUTOPAY", "2026-08-05");
    entry(owner, far, "BILL", "Water", 3, "NOT_PAID", "AUTOPAY", "2026-09-10");
    entry(owner, near, "BILL", "Water", 4, "NOT_PAID", "AUTOPAY", "2026-07-28", true);
    entry(owner, deletedPaycheck, "BILL", "Water", 5, "NOT_PAID", "AUTOPAY", "2026-07-28");
    entry(other, otherPaycheck, "BILL", "Water", 4500, "NOT_PAID", "AUTOPAY", "2026-07-28");
    entry(owner, near, "SPENDING_BUCKET", "Water", 4500, "NOT_PAID", null, null);

    ReviewQueue queue = all(owner, JULY_START, JULY_END);
    ReviewFinding finding = only(queue, ReviewCheck.RECURRING_OCCURRENCE_UNLINKED);

    // 2026-08-04 is exactly seven days after the 2026-07-28 occurrence; 08-05 is eight days. The
    // near paycheck income date (07-20) is eight days before the occurrence, so only the due date
    // qualifies the in-band entry.
    assertThat(finding.evidence().possibleMatches())
        .extracting(match -> match.entry().entryId())
        .containsExactly(edgeIn);
  }

  @Test
  void candidateSearchReportsIncompleteWhenTheLimitIsHit() {
    UUID owner = newOwner();
    definition(owner, "Water", "FIXED", 4500L, 28, true, false, 0);
    UUID paycheck = paycheck(owner, "Pay", 10000000, "2026-07-20", "ACTIVE", false);
    for (int i = 0; i < 12; i++) {
      entry(owner, paycheck, "BILL", "Water", 100 + i, "NOT_PAID", "AUTOPAY", "2026-07-28");
    }

    ReviewFinding finding =
        only(all(owner, JULY_START, JULY_END), ReviewCheck.RECURRING_OCCURRENCE_UNLINKED);

    assertThat(finding.evidence().possibleMatches()).hasSize(10);
    assertThat(finding.evidence().candidateSearch().returned()).isEqualTo(10);
    assertThat(finding.evidence().candidateSearch().limit()).isEqualTo(10);
    assertThat(finding.evidence().candidateSearch().complete()).isFalse();
    // Deterministic: ties on distance are broken by entry id.
    List<String> ids =
        finding.evidence().possibleMatches().stream()
            .map(m -> m.entry().entryId().toString())
            .toList();
    assertThat(ids).isSorted();
  }

  @Test
  void amountMatchUsesOccurrenceAmountAndStaleProvenanceIsASeparateReason() {
    UUID owner = newOwner();
    UUID electric = definition(owner, "Electric", "VARIABLE", null, 28, true, false, 0);
    occurrenceAmount(owner, electric, "2026-07-28", 9900);
    UUID paycheck = paycheck(owner, "Pay", 1000000, "2026-07-20", "ACTIVE", false);
    UUID byAmount =
        entry(owner, paycheck, "BILL", "Power co", 9900, "NOT_PAID", "AUTOPAY", "2026-07-27");
    // Imported earlier for another date (for example before the due day changed).
    UUID stale =
        entry(
            owner,
            paycheck,
            "BILL",
            "Utilities",
            1234,
            "NOT_PAID",
            "AUTOPAY",
            "2026-07-26",
            electric,
            "2026-07-15",
            false,
            false);

    ReviewFinding finding =
        only(all(owner, JULY_START, JULY_END), ReviewCheck.RECURRING_OCCURRENCE_UNLINKED);

    assertThat(finding.evidence().occurrence().amountBasis()).isEqualTo(ReviewAmountBasis.VARIABLE);
    assertThat(finding.evidence().possibleMatches())
        .extracting(m -> m.entry().entryId())
        .containsExactlyInAnyOrder(byAmount, stale);
    ReviewFinding.PossibleMatch staleMatch =
        finding.evidence().possibleMatches().stream()
            .filter(m -> m.entry().entryId().equals(stale))
            .findFirst()
            .orElseThrow();
    assertThat(staleMatch.reasons()).containsExactly(ReviewMatchReason.SAME_RECURRING_DEFINITION);
    assertThat(staleMatch.entry().provenance().sourceOccurrenceDate())
        .isEqualTo(LocalDate.of(2026, 7, 15));
    assertThat(staleMatch.entry().provenance().definitionState())
        .isEqualTo(ReviewDefinitionState.ACTIVE);
  }

  @Test
  void linkedOccurrenceIsNotReportedAsUnlinked() {
    UUID owner = newOwner();
    UUID water = definition(owner, "Water", "FIXED", 4500L, 28, true, false, 0);
    UUID paycheck = paycheck(owner, "Pay", 100000, "2026-07-20", "ACTIVE", false);
    entry(
        owner,
        paycheck,
        "BILL",
        "Water",
        4500,
        "NOT_PAID",
        "AUTOPAY",
        "2026-07-28",
        water,
        "2026-07-28",
        false,
        false);

    ReviewQueue queue = all(owner, JULY_START, JULY_END);

    assertThat(of(queue, ReviewCheck.RECURRING_OCCURRENCE_UNLINKED)).isEmpty();
    assertThat(of(queue, ReviewCheck.RECURRING_OCCURRENCE_MULTIPLE_LINKS)).isEmpty();
  }

  @Test
  void multipleLinksStayAllowedAreListedSeparatelyAndNothingChanges() {
    UUID owner = newOwner();
    UUID rent = definition(owner, "Rent", "FIXED", 90000L, 1, true, false, 0);
    UUID first = paycheck(owner, "First", 500000, "2026-07-01", "ACTIVE", false);
    UUID second = paycheck(owner, "Second", 500000, "2026-07-15", "CLOSED", false);
    UUID a =
        entry(
            owner,
            first,
            "BILL",
            "Rent",
            45000,
            "NOT_PAID",
            "AUTOPAY",
            null,
            rent,
            "2026-07-01",
            false,
            false);
    UUID b =
        entry(
            owner,
            second,
            "BILL",
            "Rent",
            45000,
            "POSTED",
            "AUTOPAY",
            null,
            rent,
            "2026-07-01",
            false,
            false);
    Map<String, Object> before = snapshot(owner);

    ReviewQueue queue = all(owner, JULY_START, JULY_END);
    ReviewFinding finding = only(queue, ReviewCheck.RECURRING_OCCURRENCE_MULTIPLE_LINKS);

    assertThat(finding.severity()).isEqualTo(ReviewSeverity.LOW);
    assertThat(finding.question()).contains("may be intentional");
    assertThat(finding.evidence().linkedEntries())
        .extracting(ReviewFinding.EntryEvidence::entryId)
        .containsExactly(a, b);
    assertThat(finding.evidence().linkedEntries())
        .extracting(ReviewFinding.EntryEvidence::status)
        .containsExactly(EntryStatus.NOT_PAID, EntryStatus.POSTED);
    assertThat(finding.evidence().linkedEntries())
        .extracting(ReviewFinding.EntryEvidence::paycheckState)
        .containsExactly(PaycheckState.ACTIVE, PaycheckState.CLOSED);
    assertThat(of(queue, ReviewCheck.RECURRING_OCCURRENCE_UNLINKED)).isEmpty();
    assertThat(snapshot(owner)).isEqualTo(before);
  }

  @Test
  void fixedVariableEstimatedAndUnknownAmountsAreDistinguished() {
    UUID owner = newOwner();
    definition(owner, "Fixed water", "FIXED", 4500L, 5, true, false, 0);
    UUID unknown = definition(owner, "Unknown electric", "VARIABLE", null, 6, true, false, 0);
    UUID entered = definition(owner, "Entered gas", "VARIABLE", 7000L, 7, true, false, 0);
    occurrenceAmount(owner, entered, "2026-07-07", 8100);
    UUID estimatedDefinition =
        definition(owner, "Estimated phone", "VARIABLE", null, 8, true, false, 0);
    UUID paycheck = paycheck(owner, "Pay", 1000000, "2026-07-10", "ACTIVE", false);
    UUID estimatedEntry =
        entry(
            owner,
            paycheck,
            "BILL",
            "Estimated phone",
            6000,
            "NOT_PAID",
            "AUTOPAY",
            null,
            estimatedDefinition,
            "2026-07-08",
            true,
            false);
    entry(owner, paycheck, "BILL", "Plain", 100, "NOT_PAID", "AUTOPAY", null);

    ReviewQueue queue = all(owner, JULY_START, JULY_END);
    List<ReviewFinding> variable = of(queue, ReviewCheck.VARIABLE_AMOUNT_NEEDS_REVIEW);

    assertThat(variable).hasSize(3);
    ReviewFinding unknownAmount =
        variable.stream()
            .filter(f -> unknown.equals(f.definitionId()) && f.entryId() == null)
            .findFirst()
            .orElseThrow();
    assertThat(unknownAmount.scope()).isEqualTo(ReviewScope.OCCURRENCE_WINDOW);
    assertThat(unknownAmount.severity()).isEqualTo(ReviewSeverity.MEDIUM);
    assertThat(unknownAmount.evidence().occurrence().amountBasis())
        .isEqualTo(ReviewAmountBasis.UNKNOWN);
    assertThat(unknownAmount.evidence().occurrence().amountMinor()).isNull();
    assertThat(unknownAmount.evidence().occurrence().amountEntered()).isFalse();
    // Estimated-definition occurrence has no saved amount either, so it is unknown at occurrence
    // level, while the linked entry carries an estimate.
    ReviewFinding estimated =
        variable.stream().filter(f -> estimatedEntry.equals(f.entryId())).findFirst().orElseThrow();
    assertThat(estimated.scope()).isEqualTo(ReviewScope.ALL_ACTIVE_PAYCHECKS);
    assertThat(estimated.evidence().entry().amountBasis()).isEqualTo(ReviewAmountBasis.ESTIMATED);
    assertThat(estimated.evidence().entry().amountEstimated()).isTrue();
    assertThat(estimated.evidence().entry().amountMinor()).isEqualTo(6000L);
    assertThat(estimated.evidence().definition().id()).isEqualTo(estimatedDefinition);
    // Fixed and entered-variable occurrences never produce an amount finding.
    assertThat(variable).noneMatch(f -> f.definitionId().equals(entered) && f.entryId() == null);
    assertThat(variable)
        .noneMatch(
            f ->
                f.evidence().definition() != null
                    && "Fixed water".equals(f.evidence().definition().name()));
    assertThat(queue.summary().get(ReviewCheck.VARIABLE_AMOUNT_NEEDS_REVIEW)).isEqualTo(3L);
  }

  @Test
  void estimatedEntryLinkedInTheWindowButOutsideActivePaychecksUsesTheWindowScope() {
    UUID owner = newOwner();
    UUID definition = definition(owner, "Phone", "VARIABLE", 6000L, 8, true, false, 0);
    UUID closed = paycheck(owner, "Closed", 100000, "2026-07-01", "CLOSED", false);
    UUID estimated =
        entry(
            owner,
            closed,
            "BILL",
            "Phone",
            6000,
            "POSTED",
            "AUTOPAY",
            null,
            definition,
            "2026-07-08",
            true,
            false);

    List<ReviewFinding> variable =
        of(all(owner, JULY_START, JULY_END), ReviewCheck.VARIABLE_AMOUNT_NEEDS_REVIEW);

    ReviewFinding entryFinding =
        variable.stream().filter(f -> estimated.equals(f.entryId())).findFirst().orElseThrow();
    assertThat(entryFinding.scope()).isEqualTo(ReviewScope.OCCURRENCE_WINDOW);
  }

  @Test
  void monthEndAndLeapYearOccurrencesAreDerivedByTheExistingPolicy() {
    UUID owner = newOwner();
    definition(owner, "Month end", "FIXED", 100L, 31, true, false, 0);

    assertThat(dates(owner, "2028-02-01", "2028-02-29")).containsExactly(LocalDate.of(2028, 2, 29));
    assertThat(dates(owner, "2028-02-01", "2028-02-28")).isEmpty();
    assertThat(dates(owner, "2027-02-01", "2027-02-28")).containsExactly(LocalDate.of(2027, 2, 28));
    assertThat(dates(owner, "2026-04-01", "2026-04-30")).containsExactly(LocalDate.of(2026, 4, 30));
    assertThat(dates(owner, "2026-05-01", "2026-05-31")).containsExactly(LocalDate.of(2026, 5, 31));
  }

  @Test
  void windowBoundariesAreInclusive() {
    UUID owner = newOwner();
    definition(owner, "Tenth", "FIXED", 100L, 10, true, false, 0);

    assertThat(dates(owner, "2026-07-10", "2026-07-20")).containsExactly(LocalDate.of(2026, 7, 10));
    assertThat(dates(owner, "2026-07-01", "2026-07-10")).containsExactly(LocalDate.of(2026, 7, 10));
    assertThat(dates(owner, "2026-07-10", "2026-07-10")).containsExactly(LocalDate.of(2026, 7, 10));
    assertThat(dates(owner, "2026-07-11", "2026-07-20")).isEmpty();
    assertThat(dates(owner, "2026-07-01", "2026-07-09")).isEmpty();
  }

  @Test
  void aNinetyThreeDayWindowIsAcceptedAndNinetyFourIsRejected() {
    UUID owner = newOwner();
    LocalDate from = LocalDate.of(2026, 7, 1);

    assertThat(review.review(owner, from, from.plusDays(92), 0, 25).through())
        .isEqualTo(from.plusDays(92));
    assertThatThrownBy(() -> review.review(owner, from, from.plusDays(93), 0, 25))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("93 days");
    assertThatThrownBy(() -> review.review(owner, from, from.minusDays(1), 0, 25))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(() -> review.review(owner, from, from, -1, 25))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(() -> review.review(owner, from, from, 0, 0))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(() -> review.review(owner, from, from, 0, 101))
        .isInstanceOf(BusinessRuleException.class);
  }

  @Test
  void changedInactiveAndDeletedDefinitionsAreShownAsProvenanceNotRewritten() {
    UUID owner = newOwner();
    UUID edited = definition(owner, "Edited", "VARIABLE", null, 8, true, false, 3);
    UUID inactive = definition(owner, "Inactive", "VARIABLE", null, 9, false, false, 1);
    UUID deleted = definition(owner, "Deleted", "VARIABLE", null, 10, true, true, 2);
    UUID paycheck = paycheck(owner, "Pay", 1000000, "2026-07-10", "ACTIVE", false);
    // Entries were created long before the definitions' last update.
    UUID editedEntry =
        entry(
            owner,
            paycheck,
            "BILL",
            "Edited",
            100,
            "NOT_PAID",
            "AUTOPAY",
            null,
            edited,
            "2026-07-08",
            true,
            false);
    UUID inactiveEntry =
        entry(
            owner,
            paycheck,
            "BILL",
            "Inactive",
            100,
            "NOT_PAID",
            "AUTOPAY",
            null,
            inactive,
            "2026-07-09",
            true,
            false);
    UUID deletedEntry =
        entry(
            owner,
            paycheck,
            "BILL",
            "Deleted",
            100,
            "NOT_PAID",
            "AUTOPAY",
            null,
            deleted,
            "2026-07-10",
            true,
            false);

    ReviewQueue queue = all(owner, JULY_START, JULY_END);
    Map<UUID, ReviewFinding> byEntry = new java.util.HashMap<>();
    of(queue, ReviewCheck.VARIABLE_AMOUNT_NEEDS_REVIEW).stream()
        .filter(f -> f.entryId() != null)
        .forEach(f -> byEntry.put(f.entryId(), f));

    ReviewFinding.Provenance editedProvenance =
        byEntry.get(editedEntry).evidence().entry().provenance();
    assertThat(editedProvenance.definitionState()).isEqualTo(ReviewDefinitionState.ACTIVE);
    assertThat(editedProvenance.currentDefinitionVersion()).isEqualTo(3L);
    assertThat(editedProvenance.definitionUpdatedAfterEntryCreated()).isTrue();
    assertThat(byEntry.get(inactiveEntry).evidence().entry().provenance().definitionState())
        .isEqualTo(ReviewDefinitionState.INACTIVE);
    assertThat(byEntry.get(deletedEntry).evidence().entry().provenance().definitionState())
        .isEqualTo(ReviewDefinitionState.DELETED);
    assertThat(byEntry.get(deletedEntry).evidence().definition().state())
        .isEqualTo(ReviewDefinitionState.DELETED);
    // Only the active, not-deleted definition generates occurrences.
    assertThat(queue.findings())
        .filteredOn(f -> f.entryId() == null && f.definitionId() != null)
        .extracting(ReviewFinding::definitionId)
        .containsOnly(edited);
    assertThat(queue.limitations())
        .anyMatch(text -> text.contains("currently active recurring definitions"));
  }

  @Test
  void softDeletedPaychecksAndEntriesNeverCountAsLinksOrFindings() {
    UUID owner = newOwner();
    UUID water = definition(owner, "Water", "FIXED", 4500L, 28, true, false, 0);
    UUID deletedPaycheck = paycheck(owner, "Gone", 90000, "2026-07-20", "ACTIVE", true);
    UUID live = paycheck(owner, "Live", 90000, "2026-07-20", "ACTIVE", false);
    entry(
        owner,
        deletedPaycheck,
        "BILL",
        "Water",
        4500,
        "NOT_PAID",
        "MANUAL",
        "2026-07-01",
        water,
        "2026-07-28",
        false,
        false);
    entry(
        owner,
        live,
        "BILL",
        "Water",
        4500,
        "NOT_PAID",
        "MANUAL",
        "2026-07-01",
        water,
        "2026-07-28",
        false,
        true);
    entry(
        owner,
        live,
        "BILL",
        "Hidden",
        100,
        "PROCESSING",
        "MANUAL",
        "2026-07-01",
        null,
        null,
        false,
        true);
    entry(
        owner,
        live,
        "SPENDING_BUCKET",
        "Hidden bucket",
        10,
        "NOT_PAID",
        null,
        null,
        null,
        null,
        false,
        true);

    ReviewQueue queue = all(owner, JULY_START, JULY_END);

    // Neither the deleted paycheck's entry nor the deleted entry covers the occurrence, and no
    // deleted record produces a paycheck-level finding.
    assertThat(only(queue, ReviewCheck.RECURRING_OCCURRENCE_UNLINKED).evidence().linkedEntries())
        .isEmpty();
    assertThat(of(queue, ReviewCheck.MANUAL_BILL_NOT_PAID_DUE)).isEmpty();
    assertThat(of(queue, ReviewCheck.PROCESSING_AGED)).isEmpty();
    assertThat(of(queue, ReviewCheck.BUCKET_OVER_BUDGET)).isEmpty();
    List<UUID> unallocated =
        of(queue, ReviewCheck.PAYCHECK_UNALLOCATED).stream()
            .map(ReviewFinding::paycheckId)
            .toList();
    assertThat(unallocated).containsExactly(live);
    assertThat(queue.totalItems()).isEqualTo(2L);
  }

  // ---- all Active paychecks
  // ----------------------------------------------------------------------

  @Test
  void fullyAllocatedPaycheckWithUnpaidEntriesIsStillReviewedWithoutAnUnallocatedFinding() {
    UUID owner = newOwner();
    UUID paycheck = paycheck(owner, "Pay", 10000, "2026-06-01", "ACTIVE", false);
    UUID manual = entry(owner, paycheck, "BILL", "Rent", 6000, "NOT_PAID", "MANUAL", "2026-07-10");
    UUID processing =
        entry(owner, paycheck, "BILL", "Transfer", 4000, "PROCESSING", "AUTOPAY", null);
    statusEvent(owner, processing, "PROCESSING", "2026-07-01T12:00:00Z", "2026-07-01T12:00:00Z");

    ReviewQueue queue = all(owner, JULY_START, JULY_END);

    assertThat(of(queue, ReviewCheck.PAYCHECK_UNALLOCATED)).isEmpty();
    assertThat(only(queue, ReviewCheck.MANUAL_BILL_NOT_PAID_DUE).entryId()).isEqualTo(manual);
    assertThat(only(queue, ReviewCheck.PROCESSING_AGED).entryId()).isEqualTo(processing);
    assertThat(queue.totalItems()).isEqualTo(2L);
  }

  @Test
  void unallocatedPaycheckHasExactEvidenceAndPostedFullyAllocatedPaycheckHasNone() {
    UUID owner = newOwner();
    UUID partly = paycheck(owner, "Partly", 10000, "2026-07-02", "ACTIVE", false);
    entry(owner, partly, "BILL", "Rent", 6500, "NOT_PAID", "AUTOPAY", null);
    UUID empty = paycheck(owner, "Empty", 2500, "2026-07-01", "ACTIVE", false);
    UUID done = paycheck(owner, "Done", 3000, "2026-06-01", "ACTIVE", false);
    entry(owner, done, "BILL", "Phone", 3000, "POSTED", "AUTOPAY", null);
    UUID overAllocated = paycheck(owner, "Over", 1000, "2026-06-02", "ACTIVE", false);
    entry(owner, overAllocated, "BILL", "Too much", 1500, "NOT_PAID", "AUTOPAY", null);
    UUID closed = paycheck(owner, "Closed", 9999, "2026-06-03", "CLOSED", false);

    List<ReviewFinding> findings =
        of(all(owner, JULY_START, JULY_END), ReviewCheck.PAYCHECK_UNALLOCATED);

    assertThat(findings).extracting(ReviewFinding::paycheckId).containsExactly(empty, partly);
    ReviewFinding.PaycheckEvidence evidence = findings.get(1).evidence().paycheck();
    assertThat(findings.get(1).severity()).isEqualTo(ReviewSeverity.LOW);
    assertThat(findings.get(1).scope()).isEqualTo(ReviewScope.ALL_ACTIVE_PAYCHECKS);
    assertThat(evidence.amountMinor()).isEqualTo(10000L);
    assertThat(evidence.allocatedMinor()).isEqualTo(6500L);
    assertThat(evidence.unallocatedMinor()).isEqualTo(3500L);
    assertThat(evidence.version()).isZero();
    assertThat(evidence.incomeDate()).isEqualTo(LocalDate.of(2026, 7, 2));
    assertThat(findings)
        .extracting(ReviewFinding::paycheckId)
        .doesNotContain(done, overAllocated, closed);
  }

  @Test
  void paycheckChecksIgnoreTheOccurrenceWindowDates() {
    UUID owner = newOwner();
    UUID old = paycheck(owner, "Old", 1000, "2020-01-01", "ACTIVE", false);

    ReviewQueue queue = all(owner, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 31));

    assertThat(only(queue, ReviewCheck.PAYCHECK_UNALLOCATED).paycheckId()).isEqualTo(old);
    assertThat(queue.limitations())
        .contains(
            "Recurring checks cover occurrences dated 2030-01-01..2030-01-31 (inclusive); paycheck"
                + " checks cover all Active paychecks regardless of date.");
  }

  @Test
  void manualBillRuleSeparatesPastDueFromDueTodayAndExcludesEverythingElse() {
    UUID owner = newOwner();
    UUID paycheck = paycheck(owner, "Pay", 100000, "2026-07-01", "ACTIVE", false);
    UUID past = entry(owner, paycheck, "BILL", "Past", 100, "NOT_PAID", "MANUAL", "2026-07-18");
    UUID today = entry(owner, paycheck, "BILL", "Today", 100, "NOT_PAID", "MANUAL", "2026-07-19");
    entry(owner, paycheck, "BILL", "Tomorrow", 100, "NOT_PAID", "MANUAL", "2026-07-20");
    entry(owner, paycheck, "BILL", "Undated", 100, "NOT_PAID", "MANUAL", null);
    entry(owner, paycheck, "BILL", "Autopay", 100, "NOT_PAID", "AUTOPAY", "2026-07-01");
    entry(owner, paycheck, "BILL", "Posted", 100, "POSTED", "MANUAL", "2026-07-01");
    entry(owner, paycheck, "BILL", "Processing", 100, "PROCESSING", "MANUAL", "2026-07-01");
    UUID closed = paycheck(owner, "Closed", 100, "2026-06-01", "CLOSED", false);
    entry(owner, closed, "BILL", "In history", 100, "NOT_PAID", "MANUAL", "2026-06-01");

    List<ReviewFinding> findings =
        of(all(owner, JULY_START, JULY_END), ReviewCheck.MANUAL_BILL_NOT_PAID_DUE);

    assertThat(findings).extracting(ReviewFinding::entryId).containsExactly(past, today);
    assertThat(findings)
        .extracting(ReviewFinding::severity)
        .containsExactly(ReviewSeverity.HIGH, ReviewSeverity.MEDIUM);
    assertThat(findings.get(0).primaryDate()).isEqualTo(LocalDate.of(2026, 7, 18));
    assertThat(findings.get(0).evidence().entry().dueDate()).isEqualTo(LocalDate.of(2026, 7, 18));
    assertThat(findings.get(0).evidence().entry().version()).isZero();
  }

  @Test
  void processingAgeUsesTheEffectiveDateInOwnerLocalTimeAndKeepsRecordedAtSeparate() {
    UUID owner = newOwner();
    UUID paycheck = paycheck(owner, "Pay", 100000, "2026-07-01", "ACTIVE", false);
    // Old effective date, entered (recorded) today: aged.
    UUID backdated =
        processing(owner, paycheck, "Backdated", "2026-07-10T12:00:00Z", "2026-07-19T20:00:00Z");
    // Recent effective date, system-recorded long ago: not aged.
    processing(owner, paycheck, "Forward dated", "2026-07-18T12:00:00Z", "2026-07-01T00:00:00Z");
    // 02:00Z on the 17th is still the 16th in Indianapolis: exactly three days, so aged.
    UUID zoneEdge =
        processing(owner, paycheck, "Zone edge", "2026-07-17T02:00:00Z", "2026-07-17T02:00:00Z");
    // Local 17th: two days, not aged.
    processing(owner, paycheck, "Not yet", "2026-07-17T05:00:00Z", "2026-07-17T05:00:00Z");
    // Latest event is not a move to Processing: ignored like the Home attention rule.
    UUID stale = entry(owner, paycheck, "BILL", "Stale", 100, "PROCESSING", "AUTOPAY", null);
    statusEvent(owner, stale, "NOT_PAID", "2026-06-01T00:00:00Z", "2026-06-01T00:00:00Z");

    List<ReviewFinding> findings =
        of(all(owner, JULY_START, JULY_END), ReviewCheck.PROCESSING_AGED);

    assertThat(findings).extracting(ReviewFinding::entryId).containsExactly(backdated, zoneEdge);
    ReviewFinding.ProcessingEvidence evidence = findings.get(0).evidence().processing();
    assertThat(evidence.effectiveAt()).isEqualTo(Instant.parse("2026-07-10T12:00:00Z"));
    assertThat(evidence.recordedAt()).isEqualTo(Instant.parse("2026-07-19T20:00:00Z"));
    assertThat(evidence.processingSinceDate()).isEqualTo(LocalDate.of(2026, 7, 10));
    assertThat(evidence.agedOnOrBeforeDate()).isEqualTo(LocalDate.of(2026, 7, 16));
    assertThat(evidence.daysProcessing()).isEqualTo(9L);
    assertThat(findings.get(0).primaryDate()).isEqualTo(LocalDate.of(2026, 7, 10));
    assertThat(findings.get(1).evidence().processing().processingSinceDate())
        .isEqualTo(LocalDate.of(2026, 7, 16));
  }

  @Test
  void overBudgetBucketsMirrorTheHomeRuleAndUseExactMoney() {
    UUID owner = newOwner();
    UUID paycheck = paycheck(owner, "Pay", 1000000, "2026-07-01", "ACTIVE", false);
    UUID over =
        entry(owner, paycheck, "SPENDING_BUCKET", "Groceries", 10000, "NOT_PAID", null, null);
    bucketTransaction(owner, over, 7000, false);
    bucketTransaction(owner, over, 4500, false);
    bucketTransaction(owner, over, 99999, true);
    UUID exact = entry(owner, paycheck, "SPENDING_BUCKET", "Exact", 5000, "NOT_PAID", null, null);
    bucketTransaction(owner, exact, 5000, false);
    UUID posted =
        entry(owner, paycheck, "SPENDING_BUCKET", "Posted over", 100, "POSTED", null, null);
    bucketTransaction(owner, posted, 900, false);
    entry(owner, paycheck, "SPENDING_BUCKET", "Untouched", 100, "NOT_PAID", null, null);

    List<ReviewFinding> findings =
        of(all(owner, JULY_START, JULY_END), ReviewCheck.BUCKET_OVER_BUDGET);

    assertThat(findings).hasSize(1);
    ReviewFinding finding = findings.get(0);
    assertThat(finding.entryId()).isEqualTo(over);
    assertThat(finding.severity()).isEqualTo(ReviewSeverity.MEDIUM);
    assertThat(finding.evidence().bucket().budgetMinor()).isEqualTo(10000L);
    assertThat(finding.evidence().bucket().spentMinor()).isEqualTo(11500L);
    assertThat(finding.evidence().bucket().remainingMinor()).isEqualTo(-1500L);
    assertThat(finding.evidence().bucket().overageMinor()).isEqualTo(1500L);
    assertThat(finding.evidence().entry().version()).isZero();
  }

  // ---- isolation, money, ordering, paging
  // --------------------------------------------------------

  @Test
  void otherOwnersDataNeverAppearsAndNeverChangesTotalsOrSummary() {
    UUID owner = newOwner();
    UUID other = newOwner();
    Fixture.everything(this, other);
    UUID mine = paycheck(owner, "Mine", 5000, "2026-07-01", "ACTIVE", false);

    ReviewQueue queue = all(owner, JULY_START, JULY_END);
    ReviewQueue otherQueue = all(other, JULY_START, JULY_END);

    assertThat(queue.findings()).hasSize(1);
    assertThat(queue.findings().get(0).paycheckId()).isEqualTo(mine);
    assertThat(queue.totalItems()).isEqualTo(1L);
    assertThat(queue.summary().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1L);
    assertThat(queue.summary().get(ReviewCheck.PAYCHECK_UNALLOCATED)).isEqualTo(1L);
    assertThat(otherQueue.totalItems()).isGreaterThan(5L);
    assertThat(otherQueue.findings().toString()).doesNotContain(mine.toString());
    // Totals do not leak through paging either.
    assertThat(review.review(owner, JULY_START, JULY_END, 3, 25).totalItems()).isEqualTo(1L);
  }

  @Test
  void amountsNearTheSigned64BitLimitStayExactAndOverflowIsReportedNotWrapped() {
    UUID owner = newOwner();
    UUID paycheck = paycheck(owner, "Huge", Long.MAX_VALUE, "2026-07-01", "ACTIVE", false);
    entry(owner, paycheck, "BILL", "Almost all", Long.MAX_VALUE - 5, "NOT_PAID", "AUTOPAY", null);

    ReviewFinding finding =
        only(all(owner, JULY_START, JULY_END), ReviewCheck.PAYCHECK_UNALLOCATED);

    assertThat(finding.evidence().paycheck().amountMinor()).isEqualTo(Long.MAX_VALUE);
    assertThat(finding.evidence().paycheck().allocatedMinor()).isEqualTo(Long.MAX_VALUE - 5);
    assertThat(finding.evidence().paycheck().unallocatedMinor()).isEqualTo(5L);

    UUID overflowOwner = newOwner();
    UUID bad = paycheck(overflowOwner, "Overflow", Long.MAX_VALUE, "2026-07-01", "ACTIVE", false);
    entry(overflowOwner, bad, "BILL", "A", Long.MAX_VALUE - 1, "NOT_PAID", "AUTOPAY", null);
    entry(overflowOwner, bad, "BILL", "B", Long.MAX_VALUE - 1, "NOT_PAID", "AUTOPAY", null);
    assertThatThrownBy(() -> all(overflowOwner, JULY_START, JULY_END))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            exception -> assertThat(exception.code()).isEqualTo("MONEY_AMOUNT_OVERFLOW"));
  }

  @Test
  void orderingIsBySeverityThenDateThenCodeThenIdsAndIsRepeatable() {
    UUID owner = newOwner();
    definition(owner, "Water", "FIXED", 4500L, 28, true, false, 0);
    UUID early = paycheck(owner, "Early", 50000, "2026-07-02", "ACTIVE", false);
    UUID late = paycheck(owner, "Late", 50000, "2026-07-12", "ACTIVE", false);
    entry(owner, early, "BILL", "Manual late", 100, "NOT_PAID", "MANUAL", "2026-07-15");
    entry(owner, early, "BILL", "Manual early", 100, "NOT_PAID", "MANUAL", "2026-07-05");
    UUID processing =
        processing(owner, late, "Slow", "2026-07-01T12:00:00Z", "2026-07-01T12:00:00Z");

    ReviewQueue first = all(owner, JULY_START, JULY_END);
    ReviewQueue second = all(owner, JULY_START, JULY_END);

    assertThat(first.findings()).isEqualTo(second.findings());
    assertThat(first.findings())
        .extracting(f -> f.check() + "@" + f.primaryDate())
        .containsExactly(
            "MANUAL_BILL_NOT_PAID_DUE@2026-07-05",
            "MANUAL_BILL_NOT_PAID_DUE@2026-07-15",
            "PROCESSING_AGED@2026-07-01",
            "RECURRING_OCCURRENCE_UNLINKED@2026-07-28",
            "PAYCHECK_UNALLOCATED@2026-07-02",
            "PAYCHECK_UNALLOCATED@2026-07-12");
    assertThat(first.findings()).extracting(ReviewFinding::severity).isSorted();
    assertThat(first.findings().get(2).entryId()).isEqualTo(processing);
  }

  @Test
  void paginationTotalsAreConsistentAcrossPagesAndSummaryCoversTheWholeSnapshot() {
    UUID owner = newOwner();
    for (int i = 1; i <= 7; i++) {
      paycheck(owner, "P" + i, 1000 + i, "2026-07-0" + i, "ACTIVE", false);
    }
    UUID manualPaycheck = paycheck(owner, "Manual", 100, "2026-07-10", "ACTIVE", false);
    entry(owner, manualPaycheck, "BILL", "Rent", 100, "NOT_PAID", "MANUAL", "2026-07-01");

    ReviewQueue everything = review.review(owner, JULY_START, JULY_END, 0, 100);
    List<ReviewFinding> stitched = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      ReviewQueue slice = review.review(owner, JULY_START, JULY_END, page, 3);
      assertThat(slice.totalItems()).isEqualTo(8L);
      assertThat(slice.totalPages()).isEqualTo(3);
      assertThat(slice.hasNext()).isEqualTo(page < 2);
      assertThat(slice.summary()).isEqualTo(everything.summary());
      stitched.addAll(slice.findings());
    }

    assertThat(stitched).isEqualTo(everything.findings());
    assertThat(everything.findings()).hasSize(8);
    assertThat(everything.summary().get(ReviewCheck.PAYCHECK_UNALLOCATED)).isEqualTo(7L);
    assertThat(everything.summary().get(ReviewCheck.MANUAL_BILL_NOT_PAID_DUE)).isEqualTo(1L);
    ReviewQueue beyond = review.review(owner, JULY_START, JULY_END, 9, 3);
    assertThat(beyond.findings()).isEmpty();
    assertThat(beyond.totalItems()).isEqualTo(8L);
    assertThat(beyond.hasNext()).isFalse();
    assertThat(everything.limitations())
        .contains(
            "Each page is computed in its own snapshot; separate pages may reflect different data.");
    ReviewQueue empty = review.review(newOwner(), JULY_START, JULY_END, 0, 10);
    assertThat(empty.totalItems()).isZero();
    assertThat(empty.totalPages()).isZero();
    assertThat(empty.summary().values()).allMatch(count -> count == 0L);
  }

  @Test
  void everyResultStatesTheNoBankFeedAndUnenteredActivityLimitations() {
    ReviewQueue queue = all(newOwner(), JULY_START, JULY_END);

    assertThat(queue.limitations())
        .contains(
            "No bank feed: findings are not verified against bank activity.",
            "Unsaved, unsynchronized, or unentered activity may exist.",
            "Unlinked is not unpaid; multiple links may be intentional; possible matches are not"
                + " confirmed.",
            "Occurrences come only from currently active recurring definitions.");
    assertThat(queue.timeZone()).isEqualTo("America/Indianapolis");
    assertThat(queue.asOfDate()).isEqualTo(TODAY);
  }

  @Test
  void reviewWritesNothingToLedgerHistoryOrAuditTables() {
    UUID owner = newOwner();
    UUID water = definition(owner, "Water", "FIXED", 4500L, 28, true, false, 0);
    UUID variable = definition(owner, "Electric", "VARIABLE", null, 12, true, false, 0);
    occurrenceAmount(owner, variable, "2026-07-12", 7000);
    UUID paycheck = paycheck(owner, "Pay", 100000, "2026-07-01", "ACTIVE", false);
    entry(
        owner,
        paycheck,
        "BILL",
        "Water",
        4500,
        "NOT_PAID",
        "MANUAL",
        "2026-07-01",
        water,
        "2026-07-28",
        true,
        false);
    entry(
        owner,
        paycheck,
        "BILL",
        "Water",
        4500,
        "NOT_PAID",
        "MANUAL",
        "2026-07-01",
        water,
        "2026-07-28",
        false,
        false);
    processing(owner, paycheck, "Slow", "2026-07-01T12:00:00Z", "2026-07-01T12:00:00Z");
    UUID bucket = entry(owner, paycheck, "SPENDING_BUCKET", "Food", 100, "NOT_PAID", null, null);
    bucketTransaction(owner, bucket, 500, false);
    Map<String, Object> before = snapshot(owner);

    for (int i = 0; i < 3; i++) {
      assertThat(all(owner, JULY_START, JULY_END).totalItems()).isGreaterThan(4L);
    }

    assertThat(snapshot(owner)).isEqualTo(before);
    assertThat(before.get("paycheck_entries")).isEqualTo(4L);
    assertThat(before.get("entry_status_events")).isEqualTo(1L);
  }

  @Test
  void reviewRunsInOneReadOnlyRepeatableReadTransaction() {
    UUID owner = newOwner();
    paycheck(owner, "Pay", 100, "2026-07-01", "ACTIVE", false);

    review.review(owner, JULY_START, JULY_END, 0, 25);

    assertThat(clock.probes).isNotEmpty();
    assertThat(clock.probes)
        .allSatisfy(
            probe -> {
              assertThat(probe.springIsolation())
                  .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
              assertThat(probe.springReadOnly()).isTrue();
              assertThat(probe.databaseIsolation()).isEqualTo("repeatable read");
              assertThat(probe.databaseReadOnly()).isEqualTo("on");
            });
  }

  // ---- helpers
  // ------------------------------------------------------------------------------------

  private ReviewQueue all(UUID owner, LocalDate from, LocalDate through) {
    return review.review(owner, from, through, 0, 100);
  }

  private List<LocalDate> dates(UUID owner, String from, String through) {
    return of(
            all(owner, LocalDate.parse(from), LocalDate.parse(through)),
            ReviewCheck.RECURRING_OCCURRENCE_UNLINKED)
        .stream()
        .map(ReviewFinding::primaryDate)
        .toList();
  }

  private static List<ReviewFinding> of(ReviewQueue queue, ReviewCheck check) {
    return queue.findings().stream().filter(f -> f.check() == check).toList();
  }

  private static ReviewFinding only(ReviewQueue queue, ReviewCheck check) {
    List<ReviewFinding> found = of(queue, check);
    assertThat(found).as("findings for %s", check).hasSize(1);
    return found.get(0);
  }

  private Map<String, Object> snapshot(UUID owner) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    for (String table :
        List.of(
            "paychecks",
            "paycheck_entries",
            "entry_status_events",
            "recurring_bill_definitions",
            "recurring_bill_occurrence_amounts",
            "bucket_transactions")) {
      result.put(
          table,
          jdbc.queryForObject(
              "select count(*) from " + table + " where owner_id = ?", Long.class, owner));
    }
    result.put(
        "audit_events",
        jdbc.queryForObject(
            "select count(*) from audit_events where owner_id = ?", Long.class, owner));
    result.put(
        "entry_versions",
        jdbc.queryForList(
            "select id, version, updated_at from paycheck_entries where owner_id = ? order by id",
            owner));
    result.put(
        "paycheck_versions",
        jdbc.queryForList(
            "select id, version, updated_at from paychecks where owner_id = ? order by id", owner));
    result.put(
        "definition_versions",
        jdbc.queryForList(
            "select id, version, updated_at from recurring_bill_definitions where owner_id = ? order by id",
            owner));
    return result;
  }

  UUID newOwner() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into user_accounts (id, email, password_hash, display_name, role, created_at, updated_at)
        values (?, ?, 'not-a-real-hash', 'Synthetic', 'USER', now(), now())
        """,
        id,
        "review-" + id + "@yuuka.test.invalid");
    return id;
  }

  UUID paycheck(
      UUID owner, String name, long amount, String incomeDate, String state, boolean deleted) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into paychecks
          (id, owner_id, name, amount_minor, income_date, state, created_at, updated_at, deleted_at, version)
        values (?, ?, ?, ?, cast(? as date), ?, now(), now(), ?, 0)
        """,
        id,
        owner,
        name,
        amount,
        incomeDate,
        state,
        deleted ? Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")) : null);
    return id;
  }

  UUID entry(
      UUID owner,
      UUID paycheck,
      String type,
      String name,
      long amount,
      String status,
      String paymentMethod,
      String dueDate) {
    return entry(
        owner,
        paycheck,
        type,
        name,
        amount,
        status,
        paymentMethod,
        dueDate,
        null,
        null,
        false,
        false);
  }

  private UUID entry(
      UUID owner,
      UUID paycheck,
      String type,
      String name,
      long amount,
      String status,
      String paymentMethod,
      String dueDate,
      boolean deleted) {
    return entry(
        owner,
        paycheck,
        type,
        name,
        amount,
        status,
        paymentMethod,
        dueDate,
        null,
        null,
        false,
        deleted);
  }

  UUID entry(
      UUID owner,
      UUID paycheck,
      String type,
      String name,
      long amount,
      String status,
      String paymentMethod,
      String dueDate,
      UUID definition,
      String occurrenceDate,
      boolean estimated,
      boolean deleted) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into paycheck_entries
          (id, owner_id, paycheck_id, entry_type, payment_method, name, amount_minor, status,
           position, due_date, source_recurring_bill_definition_id, source_recurring_occurrence_date,
           amount_estimated, created_at, updated_at, deleted_at, version)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as date), ?, cast(? as date), ?,
                timestamptz '2026-01-01 00:00:00+00', timestamptz '2026-01-01 00:00:00+00', ?, 0)
        """,
        id,
        owner,
        paycheck,
        type,
        paymentMethod,
        name,
        amount,
        status,
        nextPosition(paycheck),
        dueDate,
        definition,
        occurrenceDate,
        estimated,
        deleted ? Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")) : null);
    return id;
  }

  private int nextPosition(UUID paycheck) {
    return jdbc.queryForObject(
        "select coalesce(max(position), -1) + 1 from paycheck_entries where paycheck_id = ?",
        Integer.class,
        paycheck);
  }

  UUID definition(
      UUID owner,
      String name,
      String mode,
      Long typical,
      int dueDay,
      boolean active,
      boolean deleted,
      long version) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into recurring_bill_definitions
          (id, owner_id, name, typical_amount_minor, amount_mode, payment_method, due_day, active,
           version, created_at, updated_at, deleted_at)
        values (?, ?, ?, ?, ?, 'AUTOPAY', ?, ?, ?, timestamptz '2026-01-01 00:00:00+00',
                timestamptz '2026-06-01 00:00:00+00', ?)
        """,
        id,
        owner,
        name,
        typical,
        mode,
        dueDay,
        active,
        version,
        deleted ? Timestamp.from(Instant.parse("2026-06-15T00:00:00Z")) : null);
    return id;
  }

  private void occurrenceAmount(UUID owner, UUID definition, String date, long amount) {
    jdbc.update(
        """
        insert into recurring_bill_occurrence_amounts
          (id, owner_id, definition_id, occurrence_date, amount_minor, version, created_at, updated_at)
        values (?, ?, ?, cast(? as date), ?, 0, now(), now())
        """,
        UUID.randomUUID(),
        owner,
        definition,
        date,
        amount);
  }

  private UUID processing(
      UUID owner, UUID paycheck, String name, String effectiveAt, String recordedAt) {
    UUID id = entry(owner, paycheck, "BILL", name, 100, "PROCESSING", "AUTOPAY", null);
    statusEvent(owner, id, "PROCESSING", effectiveAt, recordedAt);
    return id;
  }

  private void statusEvent(
      UUID owner, UUID entry, String toStatus, String effectiveAt, String recordedAt) {
    jdbc.update(
        """
        insert into entry_status_events
          (id, owner_id, entry_id, from_status, to_status, effective_at, recorded_at)
        values (?, ?, ?, 'NOT_PAID', ?, cast(? as timestamptz), cast(? as timestamptz))
        """,
        UUID.randomUUID(),
        owner,
        entry,
        toStatus,
        effectiveAt,
        recordedAt);
  }

  private void bucketTransaction(UUID owner, UUID entry, long amount, boolean deleted) {
    jdbc.update(
        """
        insert into bucket_transactions
          (id, owner_id, entry_id, amount_minor, effective_date, created_at, updated_at, deleted_at, version)
        values (?, ?, ?, ?, '2026-07-18', now(), now(), ?, 0)
        """,
        UUID.randomUUID(),
        owner,
        entry,
        amount,
        deleted ? Timestamp.from(Instant.parse("2026-07-18T00:00:00Z")) : null);
  }

  /** Populates another owner with one of everything so isolation is meaningful. */
  private static final class Fixture {
    static void everything(BudgetReviewServiceTests t, UUID other) {
      t.definition(other, "Water", "FIXED", 4500L, 28, true, false, 0);
      t.definition(other, "Electric", "VARIABLE", null, 12, true, false, 0);
      UUID paycheck = t.paycheck(other, "Other pay", 100000, "2026-07-01", "ACTIVE", false);
      t.entry(other, paycheck, "BILL", "Water", 4500, "NOT_PAID", "MANUAL", "2026-07-01");
      UUID processing =
          t.entry(other, paycheck, "BILL", "Slow", 100, "PROCESSING", "AUTOPAY", null);
      t.jdbc.update(
          "insert into entry_status_events (id, owner_id, entry_id, from_status, to_status, effective_at, recorded_at)"
              + " values (?, ?, ?, 'NOT_PAID', 'PROCESSING', timestamptz '2026-07-01 00:00:00+00', now())",
          UUID.randomUUID(),
          other,
          processing);
      UUID bucket =
          t.entry(other, paycheck, "SPENDING_BUCKET", "Food", 100, "NOT_PAID", null, null);
      t.jdbc.update(
          "insert into bucket_transactions (id, owner_id, entry_id, amount_minor, effective_date, created_at, updated_at, version)"
              + " values (?, ?, ?, 900, '2026-07-18', now(), now(), 0)",
          UUID.randomUUID(),
          other,
          bucket);
    }
  }

  /** Test clock that also records the transaction the reading code runs in. */
  static final class ProbeClock extends Clock {
    record Probe(
        Integer springIsolation,
        boolean springReadOnly,
        String databaseIsolation,
        String databaseReadOnly) {}

    private volatile Instant instant = Instant.parse("2026-07-20T03:00:00Z");
    final List<Probe> probes = new CopyOnWriteArrayList<>();
    volatile JdbcTemplate jdbc;

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      if (jdbc != null && TransactionSynchronizationManager.isActualTransactionActive()) {
        probes.add(
            new Probe(
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                jdbc.queryForObject("show transaction_isolation", String.class),
                jdbc.queryForObject("show transaction_read_only", String.class)));
      }
      return instant;
    }
  }

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    ProbeClock probeClock() {
      return new ProbeClock();
    }
  }
}
