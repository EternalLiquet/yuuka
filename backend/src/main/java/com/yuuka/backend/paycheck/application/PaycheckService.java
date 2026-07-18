package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.api.dto.CreateEntryRequest;
import com.yuuka.backend.paycheck.api.dto.CreatePaycheckFromDraftRequest;
import com.yuuka.backend.paycheck.api.dto.CreatePaycheckRequest;
import com.yuuka.backend.paycheck.api.dto.DraftPaycheckEntryRequest;
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
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.domain.StatusTransitionPolicy;
import com.yuuka.backend.paycheck.infrastructure.JpaEntryStatusEventRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.sinkingfund.application.SinkingFundService;
import java.sql.Date;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaycheckService {
  private final JpaPaycheckRepository paychecks;
  private final JpaPaycheckEntryRepository entries;
  private final JpaEntryStatusEventRepository statusEvents;
  private final PaycheckResponseAssembler responseAssembler;
  private final PaycheckEntryMutationHelper entryMutations;
  private final PaycheckLifecycleTransitions lifecycleTransitions;
  private final PaycheckValidationHelper validations;
  private final StatusTransitionPolicy statusTransitionPolicy;
  private final OwnerLocalDateService ownerLocalDateService;
  private final PaybackService paybackService;
  private final SinkingFundService sinkingFundService;
  private final AuditService auditService;
  private final Clock clock;

  public PaycheckService(
      JpaPaycheckRepository paychecks,
      JpaPaycheckEntryRepository entries,
      JpaEntryStatusEventRepository statusEvents,
      PaycheckResponseAssembler responseAssembler,
      PaycheckEntryMutationHelper entryMutations,
      PaycheckLifecycleTransitions lifecycleTransitions,
      PaycheckValidationHelper validations,
      StatusTransitionPolicy statusTransitionPolicy,
      OwnerLocalDateService ownerLocalDateService,
      PaybackService paybackService,
      SinkingFundService sinkingFundService,
      AuditService auditService,
      Clock clock) {
    this.paychecks = paychecks;
    this.entries = entries;
    this.statusEvents = statusEvents;
    this.responseAssembler = responseAssembler;
    this.entryMutations = entryMutations;
    this.lifecycleTransitions = lifecycleTransitions;
    this.validations = validations;
    this.statusTransitionPolicy = statusTransitionPolicy;
    this.ownerLocalDateService = ownerLocalDateService;
    this.paybackService = paybackService;
    this.sinkingFundService = sinkingFundService;
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
                validations.normalizeOptional(request.source()),
                request.amountMinor(),
                request.incomeDate(),
                validations.normalizeOptional(request.notes()),
                null));
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    PaycheckResponse response = toResponse(paycheck, List.of(), asOfDate);
    auditService.append(
        ownerId, "PAYCHECK", paycheck.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional
  public PaycheckResponse createFromDraft(UUID ownerId, CreatePaycheckFromDraftRequest request) {
    List<DraftPaycheckEntryRequest> requestedEntries = request.entries();
    PaycheckMetrics proposed =
        responseAssembler.calculate(
            request.amountMinor(),
            requestedEntries.stream()
                .map(entry -> new AllocationLine(entry.amountMinor(), EntryStatus.NOT_PAID, false))
                .toList());
    validations.assertNotOverAllocated(proposed);

    Paycheck paycheck =
        paychecks.saveAndFlush(
            new Paycheck(
                ownerId,
                request.name().trim(),
                validations.normalizeOptional(request.source()),
                request.amountMinor(),
                request.incomeDate(),
                validations.normalizeOptional(request.notes()),
                null));
    List<PaycheckEntry> createdEntries = new ArrayList<>();
    Instant now = clock.instant();
    for (int index = 0; index < requestedEntries.size(); index++) {
      DraftPaycheckEntryRequest source = requestedEntries.get(index);
      PaycheckEntry entry = entryMutations.draftEntry(ownerId, paycheck.getId(), source, index);
      sinkingFundService.validateAssignment(ownerId, entry, source.sinkingFundId());
      entry = entries.saveAndFlush(entry);
      createdEntries.add(entry);
      statusEvents.save(
          new EntryStatusEvent(
              ownerId, entry.getId(), null, EntryStatus.NOT_PAID, now, now, "Created from draft"));
    }

    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    PaycheckResponse response = toResponse(paycheck, createdEntries, asOfDate);
    auditService.append(
        ownerId, "PAYCHECK", paycheck.getId(), "CREATED_FROM_DRAFT", null, null, response, null);
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
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    List<EntryResponse> responses =
        liveEntries.stream()
            .filter(entry -> status == null || entry.getStatus() == status)
            .filter(entry -> type == null || entry.getEntryType() == type)
            .map(this::toEntryResponse)
            .sorted(entryComparator(sort, ascending))
            .toList();
    return responseAssembler.toResponse(paycheck, liveEntries, asOfDate, responses);
  }

  @Transactional(readOnly = true)
  public PageResponse<PaycheckResponse> active(UUID ownerId, int page, int size) {
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    Page<Paycheck> results = paychecks.findActivePage(ownerId, listPage(page, size));
    return new PageResponse<>(
        responseAssembler.toResponses(ownerId, results.getContent(), asOfDate),
        results.getNumber(),
        results.getSize(),
        results.getTotalElements(),
        results.getTotalPages(),
        results.hasNext());
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
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    Page<Paycheck> results =
        oldestFirst
            ? paychecks.findHistoryPageOldest(
                ownerId, term, nullableSqlDate(from), nullableSqlDate(to), listPage(page, size))
            : paychecks.findHistoryPageNewest(
                ownerId, term, nullableSqlDate(from), nullableSqlDate(to), listPage(page, size));
    return new PageResponse<>(
        responseAssembler.toResponses(ownerId, results.getContent(), asOfDate),
        results.getNumber(),
        results.getSize(),
        results.getTotalElements(),
        results.getTotalPages(),
        results.hasNext());
  }

  @Transactional
  public PaycheckResponse update(UUID ownerId, UUID paycheckId, UpdatePaycheckRequest request) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    validations.requireActive(paycheck);
    validations.assertVersion(paycheck.getVersion(), request.version());
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    PaycheckMetrics proposed =
        responseAssembler.calculate(
            request.amountMinor(), responseAssembler.allocationLines(liveEntries));
    validations.assertNotOverAllocated(proposed);
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    PaycheckResponse before = toResponse(paycheck, liveEntries, asOfDate);
    paycheck.update(
        request.name().trim(),
        validations.normalizeOptional(request.source()),
        request.amountMinor(),
        request.incomeDate(),
        validations.normalizeOptional(request.notes()));
    paychecks.flush();
    PaycheckResponse after = toResponse(paycheck, liveEntries, asOfDate);
    auditService.append(ownerId, "PAYCHECK", paycheckId, "UPDATED", null, before, after, null);
    lifecycleTransitions.closeAutomaticallyIfComplete(
        ownerId, paycheck, liveEntries, clock.instant(), asOfDate);
    return toResponse(paycheck, liveEntries, asOfDate);
  }

  @Transactional
  public EntryResponse allocateLeftover(UUID ownerId, UUID paycheckId, long paycheckVersion) {
    Paycheck paycheck = requirePaycheckForUpdate(ownerId, paycheckId);
    validations.requireActive(paycheck);
    validations.assertVersion(paycheck.getVersion(), paycheckVersion);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    PaycheckMetrics metrics = calculate(paycheck, liveEntries);
    if (metrics.unallocatedMinor() <= 0) {
      throw new BusinessRuleException("There is no leftover money to allocate.");
    }

    PaycheckEntry entry =
        entries.saveAndFlush(
            entryMutations.leftoverEntry(
                ownerId,
                paycheckId,
                metrics.unallocatedMinor(),
                entries.findMaxLivePosition(paycheckId) + 1));
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
    validations.requireActive(paycheck);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    List<AllocationLine> proposed = new ArrayList<>(responseAssembler.allocationLines(liveEntries));
    proposed.add(new AllocationLine(request.amountMinor(), EntryStatus.NOT_PAID, false));
    validations.assertNotOverAllocated(
        responseAssembler.calculate(paycheck.getAmountMinor(), proposed));

    PaycheckEntry entry =
        entryMutations.newEntry(
            ownerId, paycheckId, request, entries.findMaxLivePosition(paycheckId) + 1);
    paybackService.validateAssignment(ownerId, entry, request.paybackId());
    sinkingFundService.validateAssignment(ownerId, entry, request.sinkingFundId());
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
    validations.requireActive(paycheck);
    validations.assertVersion(entry.getVersion(), request.version());
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
    validations.assertNotOverAllocated(
        responseAssembler.calculate(paycheck.getAmountMinor(), proposed));
    EntryResponse before = toEntryResponse(entry);
    UUID previousPaybackId = entry.getPaybackId();
    UUID previousSinkingFundId = entry.getSinkingFundId();
    long previousAmountMinor = entry.getAmountMinor();
    EntryStatus previousStatus = entry.getStatus();
    entryMutations.update(entry, request);
    Instant recordedAt = clock.instant();
    paybackService.syncAfterEntryUpdate(
        ownerId, entry, previousPaybackId, previousAmountMinor, previousStatus, recordedAt);
    sinkingFundService.syncAfterEntryUpdate(
        ownerId, entry, previousSinkingFundId, previousAmountMinor, previousStatus, recordedAt);
    paycheck.touch(recordedAt);
    entries.flush();
    EntryResponse after = toEntryResponse(entry);
    auditService.append(ownerId, "PAYCHECK_ENTRY", entryId, "UPDATED", null, before, after, null);
    lifecycleTransitions.closeAutomaticallyIfComplete(
        ownerId, paycheck, liveEntries, recordedAt, ownerLocalDateService.currentDate(ownerId));
    return after;
  }

  @Transactional
  public void deleteEntry(UUID ownerId, UUID entryId, long version) {
    PaycheckEntry entry = requireEntry(ownerId, entryId);
    Paycheck paycheck = requirePaycheck(ownerId, entry.getPaycheckId());
    validations.requireActive(paycheck);
    validations.assertVersion(entry.getVersion(), version);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheck.getId());
    EntryResponse before = toEntryResponse(entry);
    Instant now = clock.instant();
    if (entry.getStatus() == EntryStatus.POSTED) {
      paybackService.reversePostedEntryRepayment(ownerId, entry.getId(), now);
      sinkingFundService.reversePostedEntryContribution(ownerId, entry.getId(), now);
    }
    entry.delete(now);
    paycheck.touch(now);
    List<PaycheckEntry> remainingEntries =
        liveEntries.stream().filter(candidate -> !candidate.getId().equals(entryId)).toList();
    entries.flush();
    auditService.append(ownerId, "PAYCHECK_ENTRY", entryId, "DELETED", null, before, null, null);
    lifecycleTransitions.closeAutomaticallyIfComplete(
        ownerId, paycheck, remainingEntries, now, ownerLocalDateService.currentDate(ownerId));
  }

  @Transactional
  public EntryResponse changeStatus(UUID ownerId, UUID entryId, StatusChangeRequest request) {
    PaycheckEntry entry = requireEntry(ownerId, entryId);
    Paycheck paycheck = requirePaycheck(ownerId, entry.getPaycheckId());
    validations.requireActive(paycheck);
    if (request.version() != null) {
      validations.assertVersion(entry.getVersion(), request.version());
    }
    statusTransitionPolicy.requireChange(entry.getStatus(), request.toStatus());

    EntryResponse before = toEntryResponse(entry);
    EntryStatus previous = entry.transitionTo(request.toStatus());
    Instant recordedAt = clock.instant();
    if (previous != EntryStatus.POSTED && request.toStatus() == EntryStatus.POSTED) {
      paybackService.applyPostedEntryRepayment(ownerId, entry, recordedAt);
      sinkingFundService.applyPostedEntryContribution(ownerId, entry, recordedAt);
    } else if (previous == EntryStatus.POSTED && request.toStatus() != EntryStatus.POSTED) {
      paybackService.reversePostedEntryRepayment(ownerId, entry.getId(), recordedAt);
      sinkingFundService.reversePostedEntryContribution(ownerId, entry.getId(), recordedAt);
    }
    statusEvents.save(
        new EntryStatusEvent(
            ownerId,
            entryId,
            previous,
            request.toStatus(),
            request.effectiveAt(),
            recordedAt,
            validations.normalizeOptional(request.note())));
    paycheck.touch(recordedAt);
    entries.flush();
    List<PaycheckEntry> updatedEntries = findEntries(ownerId, paycheck.getId());
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
    lifecycleTransitions.closeAutomaticallyIfComplete(
        ownerId, paycheck, updatedEntries, recordedAt, ownerLocalDateService.currentDate(ownerId));
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
    validations.requireActive(paycheck);
    validations.assertVersion(paycheck.getVersion(), request.paycheckVersion());
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
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    return toResponse(paycheck, findEntries(ownerId, paycheckId), asOfDate);
  }

  @Transactional
  public PaycheckResponse close(UUID ownerId, UUID paycheckId, long version) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    validations.requireActive(paycheck);
    validations.assertVersion(paycheck.getVersion(), version);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    return lifecycleTransitions.close(ownerId, paycheck, liveEntries, clock.instant(), asOfDate);
  }

  @Transactional
  public PaycheckResponse reopen(UUID ownerId, UUID paycheckId, long version) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    validations.assertVersion(paycheck.getVersion(), version);
    if (paycheck.getState() == PaycheckState.ACTIVE) {
      throw new BusinessRuleException("The paycheck is already active.");
    }
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    return lifecycleTransitions.reopen(ownerId, paycheck, liveEntries, clock.instant(), asOfDate);
  }

  @Transactional
  public PaycheckResponse archive(UUID ownerId, UUID paycheckId, long version) {
    Paycheck paycheck = requirePaycheck(ownerId, paycheckId);
    validations.assertVersion(paycheck.getVersion(), version);
    List<PaycheckEntry> liveEntries = findEntries(ownerId, paycheckId);
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    return lifecycleTransitions.archive(ownerId, paycheck, liveEntries, clock.instant(), asOfDate);
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
    return responseAssembler.toResponse(paycheck, liveEntries);
  }

  private PaycheckResponse toResponse(
      Paycheck paycheck, List<PaycheckEntry> liveEntries, LocalDate asOfDate) {
    return responseAssembler.toResponse(paycheck, liveEntries, asOfDate);
  }

  public EntryResponse toEntryResponse(PaycheckEntry entry) {
    return responseAssembler.toEntryResponse(entry);
  }

  private List<PaycheckEntry> findEntries(UUID ownerId, UUID paycheckId) {
    return entries.findAllByPaycheckIdAndOwnerIdAndDeletedAtIsNullOrderByPosition(
        paycheckId, ownerId);
  }

  private PaycheckMetrics calculate(Paycheck paycheck, List<PaycheckEntry> liveEntries) {
    return responseAssembler.calculate(paycheck, liveEntries);
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

  private PageRequest listPage(int requestedPage, int requestedSize) {
    return PageRequest.of(Math.max(0, requestedPage), Math.min(Math.max(requestedSize, 1), 100));
  }

  private Date nullableSqlDate(LocalDate date) {
    return date == null ? null : Date.valueOf(date);
  }
}
