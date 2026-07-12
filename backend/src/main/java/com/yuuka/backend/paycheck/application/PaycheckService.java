package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.bucket.domain.BucketCalculator;
import com.yuuka.backend.bucket.domain.BucketMetrics;
import com.yuuka.backend.bucket.domain.BucketTransaction;
import com.yuuka.backend.bucket.infrastructure.JpaBucketTransactionRepository;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.api.dto.CreateEntryRequest;
import com.yuuka.backend.paycheck.api.dto.CreatePaycheckRequest;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.api.dto.ReorderEntriesRequest;
import com.yuuka.backend.paycheck.api.dto.StatusChangeRequest;
import com.yuuka.backend.paycheck.api.dto.StatusEventResponse;
import com.yuuka.backend.paycheck.api.dto.UpdateEntryRequest;
import com.yuuka.backend.paycheck.api.dto.UpdatePaycheckRequest;
import com.yuuka.backend.paycheck.domain.AllocationLine;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryStatusEvent;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckCalculator;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.domain.PaycheckVisibilityPolicy;
import com.yuuka.backend.paycheck.domain.StatusTransitionPolicy;
import com.yuuka.backend.paycheck.infrastructure.JpaEntryStatusEventRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaycheckService {
  private final JpaPaycheckRepository paychecks;
  private final JpaPaycheckEntryRepository entries;
  private final JpaEntryStatusEventRepository statusEvents;
  private final JpaBucketTransactionRepository bucketTransactions;
  private final PaycheckCalculator paycheckCalculator;
  private final PaycheckVisibilityPolicy visibilityPolicy;
  private final StatusTransitionPolicy statusTransitionPolicy;
  private final BucketCalculator bucketCalculator;
  private final PaybackService paybackService;
  private final AuditService auditService;
  private final Clock clock;

  public PaycheckService(
      JpaPaycheckRepository paychecks,
      JpaPaycheckEntryRepository entries,
      JpaEntryStatusEventRepository statusEvents,
      JpaBucketTransactionRepository bucketTransactions,
      PaycheckCalculator paycheckCalculator,
      PaycheckVisibilityPolicy visibilityPolicy,
      StatusTransitionPolicy statusTransitionPolicy,
      BucketCalculator bucketCalculator,
      PaybackService paybackService,
      AuditService auditService,
      Clock clock) {
    this.paychecks = paychecks;
    this.entries = entries;
    this.statusEvents = statusEvents;
    this.bucketTransactions = bucketTransactions;
    this.paycheckCalculator = paycheckCalculator;
    this.visibilityPolicy = visibilityPolicy;
    this.statusTransitionPolicy = statusTransitionPolicy;
    this.bucketCalculator = bucketCalculator;
    this.paybackService = paybackService;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional
  public PaycheckResponse create(UUID ownerId, CreatePaycheckRequest request) {
    Paycheck paycheck =
        paychecks.saveAndFlush(
            new Paycheck(
                ownerId,
                request.name().trim(),
                normalizeOptional(request.source()),
                request.amountMinor(),
                request.incomeDate(),
                normalizeOptional(request.notes()),
                null));
    PaycheckResponse response = toResponse(paycheck, List.of());
    auditService.append(
        ownerId, "PAYCHECK", paycheck.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional(readOnly = true)
  public PaycheckResponse get(
      UUID ownerId,
      UUID paycheckId,
      EntryStatus status,
      EntryType type,
      String sort,
      boolean ascending) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    List<EntryResponse> responses =
        liveEntries.stream()
            .filter(entry -> status == null || entry.getStatus() == status)
            .filter(entry -> type == null || entry.getEntryType() == type)
            .map(this::toEntryResponse)
            .sorted(entryComparator(sort, ascending))
            .toList();
    return PaycheckResponse.from(paycheck, calculate(paycheck, liveEntries), responses);
  }

  @Transactional(readOnly = true)
  public PageResponse<PaycheckResponse> active(UUID ownerId, int page, int size) {
    List<PaycheckResponse> results =
        paychecks
            .findAllByOwnerIdAndStateOrderByIncomeDateDescUpdatedAtDesc(
                ownerId, PaycheckState.ACTIVE)
            .stream()
            .map(paycheck -> toResponse(paycheck, findEntries(ownerId, paycheck.getId())))
            .filter(
                paycheck ->
                    visibilityPolicy.belongsInActive(
                        paycheck.state(), paycheck.requiresAttention(), paycheck.reopenedAt()))
            .toList();
    return paginate(results, page, size);
  }

  @Transactional(readOnly = true)
  public PageResponse<PaycheckResponse> history(
      UUID ownerId,
      String search,
      LocalDate from,
      LocalDate to,
      boolean oldestFirst,
      int page,
      int size) {
    String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
    Comparator<Paycheck> comparator =
        Comparator.comparing(Paycheck::getIncomeDate).thenComparing(Paycheck::getUpdatedAt);
    if (!oldestFirst) {
      comparator = comparator.reversed();
    }

    List<PaycheckResponse> results =
        paychecks.findAllByOwnerId(ownerId).stream()
            .filter(
                paycheck ->
                    term.isEmpty()
                        || paycheck.getName().toLowerCase(Locale.ROOT).contains(term)
                        || (paycheck.getSource() != null
                            && paycheck.getSource().toLowerCase(Locale.ROOT).contains(term)))
            .filter(paycheck -> from == null || !paycheck.getIncomeDate().isBefore(from))
            .filter(paycheck -> to == null || !paycheck.getIncomeDate().isAfter(to))
            .sorted(comparator)
            .map(paycheck -> toResponse(paycheck, findEntries(ownerId, paycheck.getId())))
            .filter(
                paycheck ->
                    visibilityPolicy.belongsInHistory(
                        paycheck.state(), paycheck.requiresAttention(), paycheck.reopenedAt()))
            .toList();
    return paginate(results, page, size);
  }

  @Transactional
  public PaycheckResponse update(UUID ownerId, UUID paycheckId, UpdatePaycheckRequest request) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    requireActive(paycheck);
    assertVersion(paycheck.getVersion(), request.version());
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    PaycheckMetrics proposed =
        paycheckCalculator.calculate(request.amountMinor(), allocationLines(liveEntries));
    assertNotOverAllocated(proposed);
    PaycheckResponse before = toResponse(paycheck, liveEntries);
    paycheck.update(
        request.name().trim(),
        normalizeOptional(request.source()),
        request.amountMinor(),
        request.incomeDate(),
        normalizeOptional(request.notes()));
    paychecks.flush();
    PaycheckResponse after = toResponse(paycheck, liveEntries);
    auditService.append(ownerId, "PAYCHECK", paycheckId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public EntryResponse allocateLeftover(UUID ownerId, UUID paycheckId, long paycheckVersion) {
    Paycheck paycheck = requirePaycheckForUpdate(ownerId, paycheckId);
    requireActive(paycheck);
    assertVersion(paycheck.getVersion(), paycheckVersion);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    PaycheckMetrics metrics = calculate(paycheck, liveEntries);
    if (metrics.unallocatedMinor() <= 0) {
      throw new BusinessRuleException("There is no leftover money to allocate.");
    }

    PaycheckEntry entry =
        entries.saveAndFlush(
            new PaycheckEntry(
                ownerId,
                paycheckId,
                EntryType.BILL,
                "LEFTOVER",
                metrics.unallocatedMinor(),
                entries.findMaxLivePosition(paycheckId) + 1,
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    Instant recordedAt = clock.instant();
    statusEvents.save(
        new EntryStatusEvent(
            ownerId,
            entry.getId(),
            null,
            EntryStatus.NOT_PAID,
            recordedAt,
            recordedAt,
            "Leftover allocated"));
    paycheck.touch(recordedAt);
    EntryResponse response = toEntryResponse(entry);
    auditService.append(
        ownerId,
        "PAYCHECK_ENTRY",
        entry.getId(),
        "LEFTOVER_ALLOCATED",
        null,
        null,
        response,
        Map.of("paycheckId", paycheckId));
    return response;
  }

  @Transactional
  public EntryResponse addEntry(UUID ownerId, UUID paycheckId, CreateEntryRequest request) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    requireActive(paycheck);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    List<AllocationLine> proposed = new ArrayList<>(allocationLines(liveEntries));
    proposed.add(new AllocationLine(request.amountMinor(), EntryStatus.NOT_PAID, false));
    assertNotOverAllocated(paycheckCalculator.calculate(paycheck.getAmountMinor(), proposed));

    PaycheckEntry entry =
        new PaycheckEntry(
            ownerId,
            paycheckId,
            request.entryType(),
            request.name().trim(),
            request.amountMinor(),
            entries.findMaxLivePosition(paycheckId) + 1,
            billValue(request.entryType(), request.dueDate()),
            billValue(request.entryType(), normalizeOptional(request.accountName())),
            billValue(request.entryType(), normalizeOptional(request.payee())),
            normalizeOptional(request.notes()),
            sinkingValue(request.entryType(), request.targetMinor()),
            sinkingValue(request.entryType(), request.targetDate()),
            request.paybackId());
    paybackService.validateAssignment(ownerId, entry, request.paybackId());
    entry = entries.saveAndFlush(entry);
    Instant recordedAt = clock.instant();
    statusEvents.save(
        new EntryStatusEvent(
            ownerId,
            entry.getId(),
            null,
            EntryStatus.NOT_PAID,
            recordedAt,
            recordedAt,
            "Entry created"));
    paycheck.touch(recordedAt);
    EntryResponse response = toEntryResponse(entry);
    auditService.append(
        ownerId, "PAYCHECK_ENTRY", entry.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional
  public EntryResponse updateEntry(UUID ownerId, UUID entryId, UpdateEntryRequest request) {
    PaycheckEntry entry = requireEntry(ownerId, entryId);
    Paycheck paycheck = requirePaycheck(ownerId, entry.getPaycheckId());
    requireActive(paycheck);
    assertVersion(entry.getVersion(), request.version());
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheck.getId());
    List<AllocationLine> proposed =
        liveEntries.stream()
            .map(
                candidate ->
                    new AllocationLine(
                        candidate.getId().equals(entryId)
                            ? request.amountMinor()
                            : candidate.getAmountMinor(),
                        candidate.getStatus(),
                        false))
            .toList();
    assertNotOverAllocated(paycheckCalculator.calculate(paycheck.getAmountMinor(), proposed));
    EntryResponse before = toEntryResponse(entry);
    UUID previousPaybackId = entry.getPaybackId();
    long previousAmountMinor = entry.getAmountMinor();
    EntryStatus previousStatus = entry.getStatus();
    entry.update(
        request.entryType(),
        request.name().trim(),
        request.amountMinor(),
        billValue(request.entryType(), request.dueDate()),
        billValue(request.entryType(), normalizeOptional(request.accountName())),
        billValue(request.entryType(), normalizeOptional(request.payee())),
        normalizeOptional(request.notes()),
        sinkingValue(request.entryType(), request.targetMinor()),
        sinkingValue(request.entryType(), request.targetDate()),
        request.paybackId());
    Instant recordedAt = clock.instant();
    paybackService.syncAfterEntryUpdate(
        ownerId, entry, previousPaybackId, previousAmountMinor, previousStatus, recordedAt);
    paycheck.touch(recordedAt);
    entries.flush();
    EntryResponse after = toEntryResponse(entry);
    auditService.append(ownerId, "PAYCHECK_ENTRY", entryId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public void deleteEntry(UUID ownerId, UUID entryId, long version) {
    PaycheckEntry entry = requireEntry(ownerId, entryId);
    Paycheck paycheck = requirePaycheck(ownerId, entry.getPaycheckId());
    requireActive(paycheck);
    assertVersion(entry.getVersion(), version);
    EntryResponse before = toEntryResponse(entry);
    Instant now = clock.instant();
    if (entry.getStatus() == EntryStatus.POSTED) {
      paybackService.reversePostedEntryRepayment(ownerId, entry.getId(), now);
    }
    entry.delete(now);
    paycheck.touch(now);
    auditService.append(ownerId, "PAYCHECK_ENTRY", entryId, "DELETED", null, before, null, null);
  }

  @Transactional
  public EntryResponse changeStatus(UUID ownerId, UUID entryId, StatusChangeRequest request) {
    PaycheckEntry entry = requireEntry(ownerId, entryId);
    Paycheck paycheck = requirePaycheck(ownerId, entry.getPaycheckId());
    requireActive(paycheck);
    if (request.version() != null) {
      assertVersion(entry.getVersion(), request.version());
    }
    statusTransitionPolicy.requireChange(entry.getStatus(), request.toStatus());

    EntryResponse before = toEntryResponse(entry);
    EntryStatus previous = entry.transitionTo(request.toStatus());
    Instant recordedAt = clock.instant();
    if (previous != EntryStatus.POSTED && request.toStatus() == EntryStatus.POSTED) {
      paybackService.applyPostedEntryRepayment(ownerId, entry, recordedAt);
    } else if (previous == EntryStatus.POSTED && request.toStatus() != EntryStatus.POSTED) {
      paybackService.reversePostedEntryRepayment(ownerId, entry.getId(), recordedAt);
    }
    statusEvents.save(
        new EntryStatusEvent(
            ownerId,
            entryId,
            previous,
            request.toStatus(),
            request.effectiveAt(),
            recordedAt,
            normalizeOptional(request.note())));
    paycheck.touch(recordedAt);
    entries.flush();
    EntryResponse after = toEntryResponse(entry);
    auditService.append(
        ownerId,
        "PAYCHECK_ENTRY",
        entryId,
        "STATUS_CHANGED",
        request.effectiveAt(),
        before,
        after,
        Map.of("note", request.note() == null ? "" : request.note()));
    return after;
  }

  @Transactional(readOnly = true)
  public PageResponse<StatusEventResponse> statusHistory(
      UUID ownerId, UUID entryId, int page, int size) {
    requireEntry(ownerId, entryId);
    PageRequest request =
        PageRequest.of(
            Math.max(0, page),
            Math.min(Math.max(size, 1), 100),
            Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.desc("id")));
    return PageResponse.from(
        statusEvents
            .findAllByEntryIdAndOwnerId(entryId, ownerId, request)
            .map(StatusEventResponse::from));
  }

  @Transactional
  public PaycheckResponse reorder(UUID ownerId, UUID paycheckId, ReorderEntriesRequest request) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    requireActive(paycheck);
    assertVersion(paycheck.getVersion(), request.paycheckVersion());
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    Set<UUID> expected =
        liveEntries.stream().map(PaycheckEntry::getId).collect(HashSet::new, Set::add, Set::addAll);
    Set<UUID> supplied = new HashSet<>(request.entryIds());
    if (supplied.size() != request.entryIds().size() || !supplied.equals(expected)) {
      throw new BusinessRuleException("Reorder must include every live entry exactly once.");
    }

    Map<UUID, Integer> before =
        liveEntries.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    PaycheckEntry::getId, PaycheckEntry::getPosition));
    int temporaryStart = liveEntries.size();
    for (int index = 0; index < liveEntries.size(); index++) {
      liveEntries.get(index).moveTo(temporaryStart + index);
    }
    entries.saveAllAndFlush(liveEntries);

    Map<UUID, PaycheckEntry> byId =
        liveEntries.stream()
            .collect(java.util.stream.Collectors.toMap(PaycheckEntry::getId, entry -> entry));
    for (int index = 0; index < request.entryIds().size(); index++) {
      byId.get(request.entryIds().get(index)).moveTo(index);
    }
    entries.saveAllAndFlush(liveEntries);
    paycheck.touch(clock.instant());
    auditService.append(
        ownerId,
        "PAYCHECK",
        paycheckId,
        "ENTRIES_REORDERED",
        null,
        before,
        request.entryIds(),
        null);
    return toResponse(paycheck, findEntries(ownerId, paycheckId));
  }

  @Transactional
  public PaycheckResponse close(UUID ownerId, UUID paycheckId, long version) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    requireActive(paycheck);
    assertVersion(paycheck.getVersion(), version);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    PaycheckMetrics metrics = calculate(paycheck, liveEntries);
    if (!metrics.fullyAllocated() || !metrics.fullyPosted()) {
      throw new BusinessRuleException(
          "A paycheck can be closed only when fully allocated and fully Posted.");
    }
    PaycheckResponse before = toResponse(paycheck, liveEntries);
    paycheck.close(clock.instant());
    paychecks.flush();
    PaycheckResponse after = toResponse(paycheck, liveEntries);
    auditService.append(ownerId, "PAYCHECK", paycheckId, "CLOSED", null, before, after, null);
    return after;
  }

  @Transactional
  public PaycheckResponse reopen(UUID ownerId, UUID paycheckId, long version) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    assertVersion(paycheck.getVersion(), version);
    if (paycheck.getState() == PaycheckState.ACTIVE) {
      throw new BusinessRuleException("The paycheck is already active.");
    }
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    PaycheckResponse before = toResponse(paycheck, liveEntries);
    paycheck.reopen(clock.instant());
    paychecks.flush();
    PaycheckResponse after = toResponse(paycheck, liveEntries);
    auditService.append(ownerId, "PAYCHECK", paycheckId, "REOPENED", null, before, after, null);
    return after;
  }

  @Transactional
  public PaycheckResponse archive(UUID ownerId, UUID paycheckId, long version) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    assertVersion(paycheck.getVersion(), version);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    PaycheckResponse before = toResponse(paycheck, liveEntries);
    paycheck.archive(clock.instant());
    paychecks.flush();
    PaycheckResponse after = toResponse(paycheck, liveEntries);
    auditService.append(ownerId, "PAYCHECK", paycheckId, "ARCHIVED", null, before, after, null);
    return after;
  }

  public Paycheck requirePaycheck(UUID ownerId, UUID paycheckId) {
    return paychecks
        .findByIdAndOwnerId(paycheckId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private Paycheck requirePaycheckForUpdate(UUID ownerId, UUID paycheckId) {
    return paychecks
        .findByIdAndOwnerIdForUpdate(paycheckId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  public PaycheckEntry requireEntry(UUID ownerId, UUID entryId) {
    return entries
        .findByIdAndOwnerIdAndDeletedAtIsNull(entryId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  public PaycheckResponse toResponse(Paycheck paycheck, List<PaycheckEntry> liveEntries) {
    return PaycheckResponse.from(
        paycheck,
        calculate(paycheck, liveEntries),
        liveEntries.stream().map(this::toEntryResponse).toList());
  }

  public EntryResponse toEntryResponse(PaycheckEntry entry) {
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

  private List<PaycheckEntry> findEntries(UUID ownerId, UUID paycheckId) {
    return entries.findAllByPaycheckIdAndOwnerIdAndDeletedAtIsNullOrderByPosition(
        paycheckId, ownerId);
  }

  private PaycheckMetrics calculate(Paycheck paycheck, List<PaycheckEntry> liveEntries) {
    return paycheckCalculator.calculate(paycheck.getAmountMinor(), allocationLines(liveEntries));
  }

  private List<AllocationLine> allocationLines(List<PaycheckEntry> liveEntries) {
    return liveEntries.stream()
        .map(entry -> new AllocationLine(entry.getAmountMinor(), entry.getStatus(), false))
        .toList();
  }

  private void assertNotOverAllocated(PaycheckMetrics metrics) {
    if (metrics.unallocatedMinor() < 0) {
      throw new BusinessRuleException(
          "PAYCHECK_OVER_ALLOCATED",
          "This would over-allocate the paycheck.",
          Map.of("amountMinor", Math.abs(metrics.unallocatedMinor()), "currencyCode", "USD"));
    }
  }

  private void assertVersion(long actual, long supplied) {
    if (actual != supplied) {
      throw new ConflictException(
          "This record changed since it was loaded. Refresh and try again.");
    }
  }

  private void requireActive(Paycheck paycheck) {
    if (paycheck.getState() != PaycheckState.ACTIVE) {
      throw new BusinessRuleException("Reopen the paycheck before changing it.");
    }
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private <T> T billValue(EntryType type, T value) {
    return type == EntryType.BILL ? value : null;
  }

  private <T> T sinkingValue(EntryType type, T value) {
    return type == EntryType.SINKING_FUND ? value : null;
  }

  private Comparator<EntryResponse> entryComparator(String sort, boolean ascending) {
    Comparator<EntryResponse> comparator =
        switch (sort == null ? "custom" : sort.toLowerCase(Locale.ROOT)) {
          case "amount" -> Comparator.comparingLong(EntryResponse::amountMinor);
          case "status" -> Comparator.comparing(entry -> entry.status().ordinal());
          case "due-date" ->
              Comparator.comparing(
                  EntryResponse::dueDate, Comparator.nullsLast(Comparator.naturalOrder()));
          case "last-edited" -> Comparator.comparing(EntryResponse::updatedAt);
          default -> Comparator.comparingInt(EntryResponse::position);
        };
    return ascending ? comparator : comparator.reversed();
  }

  private <T> PageResponse<T> paginate(List<T> items, int requestedPage, int requestedSize) {
    int page = Math.max(0, requestedPage);
    int size = Math.min(Math.max(requestedSize, 1), 100);
    int start = Math.min(page * size, items.size());
    int end = Math.min(start + size, items.size());
    int totalPages = items.isEmpty() ? 0 : (int) Math.ceil((double) items.size() / size);
    return new PageResponse<>(
        items.subList(start, end), page, size, items.size(), totalPages, page + 1 < totalPages);
  }
}
