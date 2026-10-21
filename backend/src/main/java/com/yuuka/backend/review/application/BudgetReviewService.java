package com.yuuka.backend.review.application;

import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.bucket.domain.BucketCalculator;
import com.yuuka.backend.bucket.domain.BucketMetrics;
import com.yuuka.backend.bucket.infrastructure.BucketTransactionTotalProjection;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.paycheck.domain.AllocationLine;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryStatusEvent;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckCalculator;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.domain.ProcessingAgePolicy;
import com.yuuka.backend.paycheck.infrastructure.JpaEntryStatusEventRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportSummaryResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillOccurrenceResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillTimelineResponse;
import com.yuuka.backend.recurring.application.RecurringBillService;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import com.yuuka.backend.recurring.domain.RecurringBillDefinition;
import com.yuuka.backend.recurring.infrastructure.JpaRecurringBillDefinitionRepository;
import com.yuuka.backend.review.domain.ReviewAmountBasis;
import com.yuuka.backend.review.domain.ReviewCheck;
import com.yuuka.backend.review.domain.ReviewDefinitionState;
import com.yuuka.backend.review.domain.ReviewFinding;
import com.yuuka.backend.review.domain.ReviewFinding.BucketEvidence;
import com.yuuka.backend.review.domain.ReviewFinding.CandidateSearch;
import com.yuuka.backend.review.domain.ReviewFinding.DefinitionEvidence;
import com.yuuka.backend.review.domain.ReviewFinding.EntryEvidence;
import com.yuuka.backend.review.domain.ReviewFinding.Evidence;
import com.yuuka.backend.review.domain.ReviewFinding.OccurrenceEvidence;
import com.yuuka.backend.review.domain.ReviewFinding.PaycheckEvidence;
import com.yuuka.backend.review.domain.ReviewFinding.PossibleMatch;
import com.yuuka.backend.review.domain.ReviewFinding.ProcessingEvidence;
import com.yuuka.backend.review.domain.ReviewFinding.Provenance;
import com.yuuka.backend.review.domain.ReviewMatchReason;
import com.yuuka.backend.review.domain.ReviewQueue;
import com.yuuka.backend.review.domain.ReviewScope;
import com.yuuka.backend.review.domain.ReviewSeverity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives a short list of bookkeeping questions from existing records. Nothing here writes: there
 * is no persisted review state, no audit event, and no automatic import, link, status change,
 * merge, or deletion. Missing coverage is never treated as proof of nonpayment, multiple links are
 * never treated as proof of duplication, and possible matches are never confirmed matches.
 *
 * <p>The whole response (owner context, every query, the full sorted finding list, the total and
 * the page slice) is computed inside one short read-only REPEATABLE READ transaction. Separate
 * pages are separate transactions and may reflect different data.
 */
@Service
public class BudgetReviewService {
  public static final int MAX_WINDOW_DAYS = 93;
  public static final int DEFAULT_SIZE = 25;
  public static final int MAX_SIZE = 100;
  public static final int MATCH_LIMIT = 10;
  public static final int MATCH_WINDOW_DAYS = 7;

  static final String MATCH_CRITERIA =
      "Live Bill entries in live paychecks whose due date or paycheck income date is within "
          + MATCH_WINDOW_DAYS
          + " days of the occurrence and that have the same name ignoring case, an amount equal to"
          + " the occurrence or typical amount, or the same recurring definition as provenance.";

  private final JpaUserAccountRepository users;
  private final OwnerLocalDateService ownerLocalDateService;
  private final RecurringBillService recurring;
  private final JpaRecurringBillDefinitionRepository definitions;
  private final JpaPaycheckRepository paychecks;
  private final JpaPaycheckEntryRepository entries;
  private final JpaEntryStatusEventRepository statusEvents;
  private final JpaBucketTransactionRepository bucketTransactions;
  private final PaycheckCalculator paycheckCalculator;
  private final BucketCalculator bucketCalculator;
  private final ProcessingAgePolicy processingAgePolicy;
  private final Clock clock;

  public BudgetReviewService(
      JpaUserAccountRepository users,
      OwnerLocalDateService ownerLocalDateService,
      RecurringBillService recurring,
      JpaRecurringBillDefinitionRepository definitions,
      JpaPaycheckRepository paychecks,
      JpaPaycheckEntryRepository entries,
      JpaEntryStatusEventRepository statusEvents,
      JpaBucketTransactionRepository bucketTransactions,
      PaycheckCalculator paycheckCalculator,
      BucketCalculator bucketCalculator,
      ProcessingAgePolicy processingAgePolicy,
      Clock clock) {
    this.users = users;
    this.ownerLocalDateService = ownerLocalDateService;
    this.recurring = recurring;
    this.definitions = definitions;
    this.paychecks = paychecks;
    this.entries = entries;
    this.statusEvents = statusEvents;
    this.bucketTransactions = bucketTransactions;
    this.paycheckCalculator = paycheckCalculator;
    this.bucketCalculator = bucketCalculator;
    this.processingAgePolicy = processingAgePolicy;
    this.clock = clock;
  }

  /** Throws a business-rule error when the inclusive occurrence window is not acceptable. */
  public static void validateWindow(LocalDate from, LocalDate through) {
    if (from == null || through == null) {
      throw new BusinessRuleException(
          "REVIEW_WINDOW_INVALID", "Choose a start and end date.", Map.of());
    }
    if (through.isBefore(from)) {
      throw new BusinessRuleException(
          "REVIEW_WINDOW_INVALID", "The end date must not be before the start date.", Map.of());
    }
    if (ChronoUnit.DAYS.between(from, through) + 1 > MAX_WINDOW_DAYS) {
      throw new BusinessRuleException(
          "REVIEW_WINDOW_INVALID",
          "The date range must be at most " + MAX_WINDOW_DAYS + " days.",
          Map.of());
    }
  }

  /** The coverage statements that accompany every result. */
  public static List<String> limitations(LocalDate from, LocalDate through) {
    return List.of(
        "No bank feed: findings are not verified against bank activity.",
        "Unsaved, unsynchronized, or unentered activity may exist.",
        "Unlinked is not unpaid; multiple links may be intentional; possible matches are not"
            + " confirmed.",
        "Occurrences come only from currently active recurring definitions.",
        "Recurring checks cover occurrences dated "
            + from
            + ".."
            + through
            + " (inclusive); paycheck checks cover all Active paychecks regardless of date.",
        "Each page is computed in its own snapshot; separate pages may reflect different data.",
        "Linked entries store no snapshot of their recurring definition; provenance shows the"
            + " definition as it is now, and the updated-after-creation flag is approximate.",
        "Manual-bill findings cover dated manual Bills due today or earlier; undated and"
            + " future-dated manual Bills are not listed here.",
        "Over-budget findings exclude Posted spending buckets, matching the Home attention rules.",
        "A Variable occurrence amount that was never entered is reported even when a linked entry"
            + " carries its own amount.");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ReviewQueue review(UUID ownerId, LocalDate from, LocalDate through, int page, int size) {
    validateWindow(from, through);
    if (page < 0) {
      throw new BusinessRuleException(
          "REVIEW_PAGE_INVALID", "Page must not be negative.", Map.of());
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new BusinessRuleException(
          "REVIEW_PAGE_INVALID", "Size must be between 1 and " + MAX_SIZE + ".", Map.of());
    }
    Instant startedAt = clock.instant();
    UserAccount owner = users.findById(ownerId).orElseThrow(ResourceNotFoundException::new);
    ZoneId zone = ownerLocalDateService.zoneId(ownerId);
    LocalDate today = LocalDate.ofInstant(startedAt, zone);

    List<ReviewFinding> findings = new ArrayList<>();
    Snapshot snapshot = new Snapshot(ownerId, from, through, today, zone);
    snapshot.load();
    snapshot.addOccurrenceFindings(findings);
    snapshot.addActivePaycheckFindings(findings);
    findings.sort(BudgetReviewService::compare);

    Map<ReviewCheck, Long> summary = new EnumMap<>(ReviewCheck.class);
    for (ReviewCheck check : ReviewCheck.values()) {
      summary.put(check, 0L);
    }
    findings.forEach(finding -> summary.merge(finding.check(), 1L, Long::sum));

    long total = findings.size();
    int totalPages = (int) ((total + size - 1) / size);
    long offset = (long) page * size;
    List<ReviewFinding> slice =
        offset >= total
            ? List.of()
            : findings.subList((int) offset, (int) Math.min(total, offset + size));
    boolean hasNext = offset + size < total;
    return new ReviewQueue(
        startedAt,
        today,
        zone.getId(),
        owner.getCurrencyCode(),
        from,
        through,
        page,
        size,
        total,
        totalPages,
        hasNext,
        summary,
        limitations(from, through),
        slice);
  }

  // ---- ordering ---------------------------------------------------------------------------------

  private static final Comparator<String> NULLS_LAST =
      Comparator.nullsLast(Comparator.naturalOrder());

  static int compare(ReviewFinding left, ReviewFinding right) {
    int result = left.severity().compareTo(right.severity());
    if (result != 0) return result;
    result =
        Comparator.nullsLast(Comparator.<LocalDate>naturalOrder())
            .compare(left.primaryDate(), right.primaryDate());
    if (result != 0) return result;
    result = left.check().compareTo(right.check());
    if (result != 0) return result;
    result = NULLS_LAST.compare(text(left.paycheckId()), text(right.paycheckId()));
    if (result != 0) return result;
    result = NULLS_LAST.compare(text(left.entryId()), text(right.entryId()));
    if (result != 0) return result;
    return NULLS_LAST.compare(text(left.definitionId()), text(right.definitionId()));
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }

  // ---- one response's worth of reads
  // --------------------------------------------------------------

  /** Loaded records for one review response; everything is read inside the caller's transaction. */
  private final class Snapshot {
    private final UUID ownerId;
    private final LocalDate from;
    private final LocalDate through;
    private final LocalDate today;
    private final ZoneId zone;

    private RecurringBillTimelineResponse timeline;
    private List<Paycheck> activePaychecks = List.of();
    private List<PaycheckEntry> activeEntries = List.of();
    private final Map<UUID, Paycheck> paycheckById = new HashMap<>();
    private final Map<UUID, PaycheckEntry> entryById = new HashMap<>();
    private final Map<UUID, RecurringBillDefinition> definitionById = new HashMap<>();
    private List<PaycheckEntry> candidates = List.of();

    private Snapshot(
        UUID ownerId, LocalDate from, LocalDate through, LocalDate today, ZoneId zone) {
      this.ownerId = ownerId;
      this.from = from;
      this.through = through;
      this.today = today;
      this.zone = zone;
    }

    private void load() {
      timeline = recurring.timeline(ownerId, from, through);
      activePaychecks = paychecks.findActivePage(ownerId, Pageable.unpaged()).getContent();
      activePaychecks.forEach(paycheck -> paycheckById.put(paycheck.getId(), paycheck));
      List<UUID> activeIds = activePaychecks.stream().map(Paycheck::getId).toList();
      activeEntries =
          activeIds.isEmpty() ? List.of() : entries.findAllLiveByPaycheckIds(ownerId, activeIds);
      activeEntries.forEach(entry -> entryById.put(entry.getId(), entry));

      Set<UUID> linkedIds = new HashSet<>();
      for (RecurringBillOccurrenceResponse occurrence : timeline.items()) {
        occurrence.imports().forEach(link -> linkedIds.add(link.entryId()));
      }
      if (!linkedIds.isEmpty()) {
        entries
            .findAllByIdInAndOwnerId(linkedIds, ownerId)
            .forEach(entry -> entryById.putIfAbsent(entry.getId(), entry));
      }
      candidates =
          entries.findLiveBillCandidatesInRange(
              ownerId, from.minusDays(MATCH_WINDOW_DAYS), through.plusDays(MATCH_WINDOW_DAYS));
      candidates.forEach(entry -> entryById.putIfAbsent(entry.getId(), entry));

      Set<UUID> missingPaychecks = new HashSet<>();
      Set<UUID> definitionIds = new HashSet<>();
      for (PaycheckEntry entry : entryById.values()) {
        if (!paycheckById.containsKey(entry.getPaycheckId())) {
          missingPaychecks.add(entry.getPaycheckId());
        }
        if (entry.getSourceRecurringBillDefinitionId() != null) {
          definitionIds.add(entry.getSourceRecurringBillDefinitionId());
        }
      }
      timeline.items().forEach(item -> definitionIds.add(item.definitionId()));
      if (!missingPaychecks.isEmpty()) {
        paychecks
            .findAllByIdInAndOwnerId(missingPaychecks, ownerId)
            .forEach(paycheck -> paycheckById.put(paycheck.getId(), paycheck));
      }
      if (!definitionIds.isEmpty()) {
        definitions
            .findAllByIdInAndOwnerId(definitionIds, ownerId)
            .forEach(definition -> definitionById.put(definition.getId(), definition));
      }
    }

    // ---- occurrence window ----------------------------------------------------------------------

    private void addOccurrenceFindings(List<ReviewFinding> findings) {
      Set<UUID> reportedEstimated = new HashSet<>();
      List<PaycheckEntry> activeEstimated =
          activeEntries.stream().filter(PaycheckEntry::isAmountEstimated).toList();
      activeEstimated.forEach(entry -> reportedEstimated.add(entry.getId()));
      for (RecurringBillOccurrenceResponse occurrence : timeline.items()) {
        List<EntryEvidence> linked = linkedEntries(occurrence);
        DefinitionEvidence definition = definitionEvidence(occurrence.definitionId());
        OccurrenceEvidence evidence = occurrenceEvidence(occurrence);
        if (linked.isEmpty()) {
          findings.add(unlinked(occurrence, definition, evidence));
        } else if (linked.size() > 1) {
          findings.add(
              finding(
                  ReviewCheck.RECURRING_OCCURRENCE_MULTIPLE_LINKS,
                  ReviewScope.OCCURRENCE_WINDOW,
                  linked.size()
                      + " entries are linked to this occurrence. Multiple links may be"
                      + " intentional, for example a split payment. Is each link expected?",
                  occurrence.occurrenceDate(),
                  null,
                  null,
                  occurrence.definitionId(),
                  new Evidence(
                      definition, evidence, null, null, linked, List.of(), null, null, null)));
        }
        if (occurrence.amountMode() == RecurringBillAmountMode.VARIABLE
            && !occurrence.amountEntered()) {
          findings.add(
              finding(
                  ReviewCheck.VARIABLE_AMOUNT_NEEDS_REVIEW,
                  ReviewScope.OCCURRENCE_WINDOW,
                  "The amount for this Variable bill occurrence has not been entered, so it is"
                      + " unknown rather than zero. Is the amount known yet?",
                  occurrence.occurrenceDate(),
                  null,
                  null,
                  occurrence.definitionId(),
                  new Evidence(
                      definition, evidence, null, null, linked, List.of(), null, null, null)));
        }
        for (EntryEvidence link : linked) {
          if (link.amountEstimated() && reportedEstimated.add(link.entryId())) {
            findings.add(
                estimatedEntry(entryById.get(link.entryId()), ReviewScope.OCCURRENCE_WINDOW));
          }
        }
      }
      for (PaycheckEntry entry : activeEstimated) {
        findings.add(estimatedEntry(entry, ReviewScope.ALL_ACTIVE_PAYCHECKS));
      }
    }

    private ReviewFinding unlinked(
        RecurringBillOccurrenceResponse occurrence,
        DefinitionEvidence definition,
        OccurrenceEvidence evidence) {
      List<PossibleMatch> all = possibleMatches(occurrence);
      List<PossibleMatch> returned = all.stream().limit(MATCH_LIMIT).toList();
      CandidateSearch search =
          new CandidateSearch(
              MATCH_CRITERIA, MATCH_LIMIT, returned.size(), all.size() <= MATCH_LIMIT);
      String question =
          "No linked entry for this occurrence. This is not proof the bill is unpaid."
              + (returned.isEmpty()
                  ? " Is it planned in a paycheck, entered another way, or not applicable?"
                  : " Is one of the possible matches the same bill, or is it still unplanned?"
                      + " Possible matches are not confirmed.");
      return finding(
          ReviewCheck.RECURRING_OCCURRENCE_UNLINKED,
          ReviewScope.OCCURRENCE_WINDOW,
          question,
          occurrence.occurrenceDate(),
          null,
          null,
          occurrence.definitionId(),
          new Evidence(definition, evidence, null, null, List.of(), returned, search, null, null));
    }

    private List<PossibleMatch> possibleMatches(RecurringBillOccurrenceResponse occurrence) {
      LocalDate date = occurrence.occurrenceDate();
      String name = normalize(occurrence.name());
      record Scored(PaycheckEntry entry, List<ReviewMatchReason> reasons, long distance) {}
      List<Scored> scored = new ArrayList<>();
      for (PaycheckEntry entry : candidates) {
        Paycheck paycheck = paycheckById.get(entry.getPaycheckId());
        if (paycheck == null || paycheck.getDeletedAt() != null) {
          continue;
        }
        long incomeDistance = Math.abs(ChronoUnit.DAYS.between(date, paycheck.getIncomeDate()));
        long dueDistance =
            entry.getDueDate() == null
                ? Long.MAX_VALUE
                : Math.abs(ChronoUnit.DAYS.between(date, entry.getDueDate()));
        long distance = Math.min(incomeDistance, dueDistance);
        if (distance > MATCH_WINDOW_DAYS) {
          continue;
        }
        List<ReviewMatchReason> reasons = new ArrayList<>();
        if (normalize(entry.getName()).equals(name)) {
          reasons.add(ReviewMatchReason.NAME_EQUALS_IGNORING_CASE);
        }
        if ((occurrence.amountMinor() != null && entry.getAmountMinor() == occurrence.amountMinor())
            || (occurrence.typicalAmountMinor() != null
                && entry.getAmountMinor() == occurrence.typicalAmountMinor())) {
          reasons.add(ReviewMatchReason.AMOUNT_EQUALS_OCCURRENCE_OR_TYPICAL);
        }
        if (occurrence.definitionId().equals(entry.getSourceRecurringBillDefinitionId())) {
          reasons.add(ReviewMatchReason.SAME_RECURRING_DEFINITION);
        }
        if (!reasons.isEmpty()) {
          scored.add(new Scored(entry, reasons, distance));
        }
      }
      scored.sort(
          Comparator.comparingLong(Scored::distance)
              .thenComparing(item -> item.entry().getId().toString()));
      return scored.stream()
          .map(item -> new PossibleMatch(entryEvidence(item.entry()), item.reasons()))
          .toList();
    }

    private List<EntryEvidence> linkedEntries(RecurringBillOccurrenceResponse occurrence) {
      List<EntryEvidence> linked = new ArrayList<>();
      for (RecurringBillImportSummaryResponse link : occurrence.imports()) {
        PaycheckEntry entry = entryById.get(link.entryId());
        Paycheck paycheck = entry == null ? null : paycheckById.get(entry.getPaycheckId());
        if (entry == null
            || entry.getDeletedAt() != null
            || paycheck == null
            || paycheck.getDeletedAt() != null) {
          continue;
        }
        linked.add(entryEvidence(entry));
      }
      linked.sort(
          Comparator.comparing(EntryEvidence::paycheckIncomeDate)
              .thenComparing(item -> item.paycheckId().toString())
              .thenComparing(item -> item.entryId().toString()));
      return linked;
    }

    private ReviewFinding estimatedEntry(PaycheckEntry entry, ReviewScope scope) {
      EntryEvidence evidence = entryEvidence(entry);
      return finding(
          ReviewCheck.VARIABLE_AMOUNT_NEEDS_REVIEW,
          scope,
          "This entry's amount is marked as an estimate. Has the actual amount been confirmed?",
          entry.getSourceRecurringOccurrenceDate(),
          entry.getPaycheckId(),
          entry.getId(),
          entry.getSourceRecurringBillDefinitionId(),
          new Evidence(
              definitionEvidence(entry.getSourceRecurringBillDefinitionId()),
              null,
              null,
              evidence,
              List.of(),
              List.of(),
              null,
              null,
              null));
    }

    // ---- all Active paychecks -------------------------------------------------------------------

    private void addActivePaycheckFindings(List<ReviewFinding> findings) {
      Map<UUID, List<PaycheckEntry>> byPaycheck =
          activeEntries.stream().collect(Collectors.groupingBy(PaycheckEntry::getPaycheckId));
      for (Paycheck paycheck : activePaychecks) {
        List<PaycheckEntry> live = byPaycheck.getOrDefault(paycheck.getId(), List.of());
        PaycheckMetrics metrics =
            paycheckCalculator.calculate(
                paycheck.getAmountMinor(),
                live.stream()
                    .map(
                        entry ->
                            new AllocationLine(entry.getAmountMinor(), entry.getStatus(), false))
                    .toList());
        if (metrics.unallocatedMinor() > 0) {
          findings.add(
              finding(
                  ReviewCheck.PAYCHECK_UNALLOCATED,
                  ReviewScope.ALL_ACTIVE_PAYCHECKS,
                  "This paycheck has money that is not allocated to any entry. Is that intended"
                      + " for now?",
                  paycheck.getIncomeDate(),
                  paycheck.getId(),
                  null,
                  null,
                  new Evidence(
                      null,
                      null,
                      new PaycheckEvidence(
                          paycheck.getId(),
                          paycheck.getName(),
                          paycheck.getVersion(),
                          paycheck.getIncomeDate(),
                          paycheck.getState(),
                          paycheck.getAmountMinor(),
                          metrics.allocatedMinor(),
                          metrics.unallocatedMinor()),
                      null,
                      List.of(),
                      List.of(),
                      null,
                      null,
                      null)));
        }
      }
      addManualBills(findings);
      addAgedProcessing(findings);
      addOverBudgetBuckets(findings);
    }

    private void addManualBills(List<ReviewFinding> findings) {
      for (PaycheckEntry entry : activeEntries) {
        if (entry.getEntryType() == EntryType.BILL
            && entry.getPaymentMethod() == EntryPaymentMethod.MANUAL
            && entry.getStatus() == EntryStatus.NOT_PAID
            && entry.getDueDate() != null
            && !entry.getDueDate().isAfter(today)) {
          boolean pastDue = entry.getDueDate().isBefore(today);
          findings.add(
              new ReviewFinding(
                  ReviewCheck.MANUAL_BILL_NOT_PAID_DUE,
                  pastDue ? ReviewSeverity.HIGH : ReviewSeverity.MEDIUM,
                  ReviewScope.ALL_ACTIVE_PAYCHECKS,
                  pastDue
                      ? "This manual bill is Not Paid and its due date has passed. Has it been"
                          + " paid outside the app, or does it still need to be paid?"
                      : "This manual bill is Not Paid and is due today. Does it still need to be"
                          + " paid?",
                  entry.getDueDate(),
                  entry.getPaycheckId(),
                  entry.getId(),
                  null,
                  entryOnly(entry)));
        }
      }
    }

    private void addAgedProcessing(List<ReviewFinding> findings) {
      List<PaycheckEntry> processing =
          activeEntries.stream()
              .filter(entry -> entry.getStatus() == EntryStatus.PROCESSING)
              .toList();
      if (processing.isEmpty()) {
        return;
      }
      Map<UUID, EntryStatusEvent> latest =
          statusEvents
              .findLatestByOwnerIdAndEntryIds(
                  ownerId, processing.stream().map(PaycheckEntry::getId).toList())
              .stream()
              .collect(Collectors.toMap(EntryStatusEvent::getEntryId, Function.identity()));
      for (PaycheckEntry entry : processing) {
        EntryStatusEvent event = latest.get(entry.getId());
        if (event == null || event.getToStatus() != EntryStatus.PROCESSING) {
          continue;
        }
        LocalDate since = LocalDate.ofInstant(event.getEffectiveAt(), zone);
        if (!processingAgePolicy.isAged(since, today)) {
          continue;
        }
        ProcessingEvidence processingEvidence =
            new ProcessingEvidence(
                event.getEffectiveAt(),
                event.getRecordedAt(),
                since,
                today.minusDays(ProcessingAgePolicy.AGED_AFTER_DAYS),
                ChronoUnit.DAYS.between(since, today));
        findings.add(
            finding(
                ReviewCheck.PROCESSING_AGED,
                ReviewScope.ALL_ACTIVE_PAYCHECKS,
                "This entry has been Processing since the effective date shown. Has it posted,"
                    + " or is it still pending?",
                since,
                entry.getPaycheckId(),
                entry.getId(),
                null,
                new Evidence(
                    null,
                    null,
                    null,
                    entryEvidence(entry),
                    List.of(),
                    List.of(),
                    null,
                    processingEvidence,
                    null)));
      }
    }

    private void addOverBudgetBuckets(List<ReviewFinding> findings) {
      List<PaycheckEntry> buckets =
          activeEntries.stream()
              .filter(entry -> entry.getEntryType() == EntryType.SPENDING_BUCKET)
              .toList();
      if (buckets.isEmpty()) {
        return;
      }
      Map<UUID, Long> spent =
          bucketTransactions
              .aggregateSpentByEntryIds(
                  ownerId, buckets.stream().map(PaycheckEntry::getId).toList())
              .stream()
              .collect(
                  Collectors.toMap(
                      BucketTransactionTotalProjection::getEntryId,
                      projection ->
                          projection.getSpentMinor() == null ? 0L : projection.getSpentMinor()));
      for (PaycheckEntry entry : buckets) {
        BucketMetrics metrics =
            bucketCalculator.calculate(
                entry.getAmountMinor(), List.of(spent.getOrDefault(entry.getId(), 0L)));
        if (metrics.overBudget() && entry.getStatus() != EntryStatus.POSTED) {
          Paycheck paycheck = paycheckById.get(entry.getPaycheckId());
          findings.add(
              finding(
                  ReviewCheck.BUCKET_OVER_BUDGET,
                  ReviewScope.ALL_ACTIVE_PAYCHECKS,
                  "This spending bucket has spent more than its budget. Is the overage expected,"
                      + " or does the budget need review?",
                  paycheck.getIncomeDate(),
                  entry.getPaycheckId(),
                  entry.getId(),
                  null,
                  new Evidence(
                      null,
                      null,
                      null,
                      entryEvidence(entry),
                      List.of(),
                      List.of(),
                      null,
                      null,
                      new BucketEvidence(
                          metrics.budgetMinor(),
                          metrics.spentMinor(),
                          metrics.remainingMinor(),
                          MoneyArithmetic.subtract(0, metrics.remainingMinor())))));
        }
      }
    }

    // ---- evidence builders ----------------------------------------------------------------------

    private Evidence entryOnly(PaycheckEntry entry) {
      return new Evidence(
          null, null, null, entryEvidence(entry), List.of(), List.of(), null, null, null);
    }

    private EntryEvidence entryEvidence(PaycheckEntry entry) {
      Paycheck paycheck = paycheckById.get(entry.getPaycheckId());
      Provenance provenance = provenance(entry);
      return new EntryEvidence(
          entry.getId(),
          entry.getPaycheckId(),
          paycheck.getName(),
          paycheck.getIncomeDate(),
          paycheck.getState(),
          entry.getName(),
          entry.getEntryType(),
          entry.getStatus(),
          entry.getAmountMinor(),
          entry.getVersion(),
          entry.getDueDate(),
          entry.getPaymentMethod(),
          entry.isAmountEstimated(),
          entryBasis(entry),
          provenance);
    }

    private ReviewAmountBasis entryBasis(PaycheckEntry entry) {
      if (entry.isAmountEstimated()) {
        return ReviewAmountBasis.ESTIMATED;
      }
      RecurringBillDefinition definition =
          entry.getSourceRecurringBillDefinitionId() == null
              ? null
              : definitionById.get(entry.getSourceRecurringBillDefinitionId());
      if (definition == null) {
        return null;
      }
      return definition.getAmountMode() == RecurringBillAmountMode.FIXED
          ? ReviewAmountBasis.FIXED
          : ReviewAmountBasis.VARIABLE;
    }

    private Provenance provenance(PaycheckEntry entry) {
      UUID definitionId = entry.getSourceRecurringBillDefinitionId();
      RecurringBillDefinition definition =
          definitionId == null ? null : definitionById.get(definitionId);
      if (definition == null) {
        return null;
      }
      return new Provenance(
          definition.getId(),
          state(definition),
          definition.getVersion(),
          definition.getName(),
          definition.getUpdatedAt().isAfter(entry.getCreatedAt()),
          entry.getSourceRecurringOccurrenceDate());
    }

    private DefinitionEvidence definitionEvidence(UUID definitionId) {
      RecurringBillDefinition definition =
          definitionId == null ? null : definitionById.get(definitionId);
      if (definition == null) {
        return null;
      }
      return new DefinitionEvidence(
          definition.getId(),
          definition.getVersion(),
          definition.getName(),
          definition.getAmountMode(),
          definition.getTypicalAmountMinor(),
          definition.getPlanningAmountMinor(),
          state(definition));
    }

    private OccurrenceEvidence occurrenceEvidence(RecurringBillOccurrenceResponse occurrence) {
      ReviewAmountBasis basis =
          occurrence.amountMode() == RecurringBillAmountMode.FIXED
              ? ReviewAmountBasis.FIXED
              : occurrence.amountEntered() ? ReviewAmountBasis.VARIABLE : ReviewAmountBasis.UNKNOWN;
      return new OccurrenceEvidence(
          occurrence.occurrenceDate(),
          occurrence.amountMinor(),
          occurrence.amountEntered(),
          basis,
          occurrence.occurrenceAmountVersion());
    }
  }

  private static ReviewDefinitionState state(RecurringBillDefinition definition) {
    if (definition.getDeletedAt() != null) {
      return ReviewDefinitionState.DELETED;
    }
    return definition.isActive() ? ReviewDefinitionState.ACTIVE : ReviewDefinitionState.INACTIVE;
  }

  private static ReviewFinding finding(
      ReviewCheck check,
      ReviewScope scope,
      String question,
      LocalDate primaryDate,
      UUID paycheckId,
      UUID entryId,
      UUID definitionId,
      Evidence evidence) {
    return new ReviewFinding(
        check,
        check.defaultSeverity(),
        scope,
        question,
        primaryDate,
        paycheckId,
        entryId,
        definitionId,
        evidence);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
