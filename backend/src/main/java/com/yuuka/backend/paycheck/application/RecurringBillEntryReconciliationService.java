package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.domain.AllocationLine;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.recurring.api.dto.CreateRecurringBillFromEntryRequest;
import com.yuuka.backend.recurring.api.dto.LinkRecurringBillRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillResponse;
import com.yuuka.backend.recurring.domain.MonthlyOccurrencePolicy;
import com.yuuka.backend.recurring.domain.RecurringBillDefinition;
import com.yuuka.backend.recurring.infrastructure.JpaRecurringBillDefinitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringBillEntryReconciliationService {
  private final JpaRecurringBillDefinitionRepository definitions;
  private final JpaPaycheckRepository paychecks;
  private final JpaPaycheckEntryRepository entries;
  private final PaycheckResponseAssembler responseAssembler;
  private final PaycheckEntryMutationHelper entryMutations;
  private final PaycheckLifecycleTransitions lifecycleTransitions;
  private final PaycheckValidationHelper validations;
  private final PaybackService paybackService;
  private final MonthlyOccurrencePolicy occurrencePolicy;
  private final OwnerLocalDateService ownerLocalDateService;
  private final AuditService auditService;
  private final Clock clock;

  public RecurringBillEntryReconciliationService(
      JpaRecurringBillDefinitionRepository definitions,
      JpaPaycheckRepository paychecks,
      JpaPaycheckEntryRepository entries,
      PaycheckResponseAssembler responseAssembler,
      PaycheckEntryMutationHelper entryMutations,
      PaycheckLifecycleTransitions lifecycleTransitions,
      PaycheckValidationHelper validations,
      PaybackService paybackService,
      MonthlyOccurrencePolicy occurrencePolicy,
      OwnerLocalDateService ownerLocalDateService,
      AuditService auditService,
      Clock clock) {
    this.definitions = definitions;
    this.paychecks = paychecks;
    this.entries = entries;
    this.responseAssembler = responseAssembler;
    this.entryMutations = entryMutations;
    this.lifecycleTransitions = lifecycleTransitions;
    this.validations = validations;
    this.paybackService = paybackService;
    this.occurrencePolicy = occurrencePolicy;
    this.ownerLocalDateService = ownerLocalDateService;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional
  public PaycheckResponse link(UUID ownerId, UUID entryId, LinkRecurringBillRequest request) {
    LockedEntry locked =
        lockEntry(ownerId, entryId, request.entryVersion(), request.paycheckVersion());
    RecurringBillDefinition definition =
        definitions
            .findByIdAndOwnerIdForUpdate(request.definitionId(), ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    validations.assertVersion(definition.getVersion(), request.definitionVersion());
    requireActive(definition);
    validateOccurrence(definition.getDueDay(), request.occurrenceDate());
    requireDuplicateConfirmation(
        ownerId,
        entryId,
        definition.getId(),
        request.occurrenceDate(),
        request.confirmDuplicateOccurrence());

    return normalizeAndLink(ownerId, locked, definition, request.occurrenceDate(), false, null);
  }

  @Transactional
  public PaycheckResponse createFromEntry(
      UUID ownerId, UUID entryId, CreateRecurringBillFromEntryRequest request) {
    LockedEntry locked =
        lockEntry(ownerId, entryId, request.entryVersion(), request.paycheckVersion());
    if (locked.entry().getSourceRecurringBillDefinitionId() != null) {
      throw new BusinessRuleException(
          "Remove the existing recurring Bill link before creating a new definition.");
    }
    validateOccurrence(request.dueDay(), request.occurrenceDate());
    assertAllocation(locked, request.typicalAmountMinor());

    RecurringBillDefinition definition =
        definitions.saveAndFlush(
            new RecurringBillDefinition(
                ownerId,
                request.name().trim(),
                request.typicalAmountMinor(),
                request.paymentMethod() == null
                    ? com.yuuka.backend.paycheck.domain.EntryPaymentMethod.AUTOPAY
                    : request.paymentMethod(),
                request.dueDay(),
                validations.normalizeOptional(request.accountName()),
                validations.normalizeOptional(request.payee()),
                validations.normalizeOptional(request.notes())));
    RecurringBillResponse definitionAfter = RecurringBillResponse.from(definition);
    auditService.append(
        ownerId,
        "RECURRING_BILL_DEFINITION",
        definition.getId(),
        "CREATED_FROM_PAYCHECK_BILL",
        null,
        null,
        definitionAfter,
        Map.of("paycheckId", locked.paycheck().getId(), "entryId", entryId));

    return normalizeAndLink(
        ownerId,
        locked,
        definition,
        request.occurrenceDate(),
        true,
        "RECURRING_BILL_CREATED_AND_LINKED");
  }

  @Transactional
  public PaycheckResponse unlink(
      UUID ownerId, UUID entryId, long entryVersion, long paycheckVersion) {
    LockedEntry locked = lockEntry(ownerId, entryId, entryVersion, paycheckVersion);
    PaycheckEntry entry = locked.entry();
    EntryResponse before = responseAssembler.toEntryResponse(entry);
    RecurringSource previous = RecurringSource.from(entry);
    if (previous.definitionId() == null) {
      throw new BusinessRuleException("This Bill is not linked to a recurring Bill.");
    }
    entry.setRecurringSource(null, null);
    Instant recordedAt = clock.instant();
    locked.paycheck().touch(recordedAt);
    entries.flush();
    EntryResponse after = responseAssembler.toEntryResponse(entry);
    auditService.append(
        ownerId,
        "PAYCHECK_ENTRY",
        entryId,
        "RECURRING_BILL_LINK_REMOVED",
        null,
        before,
        after,
        new RecurringLinkAuditMetadata(
            locked.paycheck().getId(), previous, RecurringSource.from(entry), false));
    lifecycleTransitions.closeAutomaticallyIfComplete(
        ownerId,
        locked.paycheck(),
        locked.liveEntries(),
        recordedAt,
        ownerLocalDateService.currentDate(ownerId));
    return responseAssembler.toResponse(locked.paycheck(), locked.liveEntries());
  }

  private PaycheckResponse normalizeAndLink(
      UUID ownerId,
      LockedEntry locked,
      RecurringBillDefinition definition,
      LocalDate occurrenceDate,
      boolean definitionCreated,
      String forcedAction) {
    PaycheckEntry entry = locked.entry();
    assertAllocation(locked, definition.getTypicalAmountMinor());
    EntryResponse before = responseAssembler.toEntryResponse(entry);
    RecurringSource previous = RecurringSource.from(entry);
    UUID previousPaybackId = entry.getPaybackId();
    long previousAmountMinor = entry.getAmountMinor();
    EntryStatus previousStatus = entry.getStatus();
    entryMutations.normalizeRecurringBill(entry, definition, occurrenceDate);
    Instant recordedAt = clock.instant();
    paybackService.syncAfterEntryUpdate(
        ownerId, entry, previousPaybackId, previousAmountMinor, previousStatus, recordedAt);
    locked.paycheck().touch(recordedAt);
    entries.flush();
    EntryResponse after = responseAssembler.toEntryResponse(entry);
    String action =
        forcedAction != null
            ? forcedAction
            : previous.definitionId() == null
                ? "RECURRING_BILL_LINKED"
                : "RECURRING_BILL_LINK_CHANGED";
    auditService.append(
        ownerId,
        "PAYCHECK_ENTRY",
        entry.getId(),
        action,
        null,
        before,
        after,
        new RecurringLinkAuditMetadata(
            locked.paycheck().getId(), previous, RecurringSource.from(entry), definitionCreated));
    lifecycleTransitions.closeAutomaticallyIfComplete(
        ownerId,
        locked.paycheck(),
        locked.liveEntries(),
        recordedAt,
        ownerLocalDateService.currentDate(ownerId));
    return responseAssembler.toResponse(locked.paycheck(), locked.liveEntries());
  }

  private LockedEntry lockEntry(
      UUID ownerId, UUID entryId, long entryVersion, long paycheckVersion) {
    PaycheckEntry discoveredEntry =
        entries
            .findByIdAndOwnerIdAndDeletedAtIsNull(entryId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    EntryLockCandidate candidate =
        new EntryLockCandidate(discoveredEntry.getPaycheckId(), discoveredEntry.getPaybackId());
    if (candidate.paybackId() != null) {
      paybackService.lockForRecurringReconciliation(ownerId, candidate.paybackId());
    }
    Paycheck paycheck =
        paychecks
            .findByIdAndOwnerIdForUpdate(candidate.paycheckId(), ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    PaycheckEntry entry =
        entries
            .findLiveByIdAndOwnerIdForUpdate(entryId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    if (!entry.getPaycheckId().equals(candidate.paycheckId())
        || !Objects.equals(entry.getPaybackId(), candidate.paybackId())) {
      throw new ConflictException(
          "This record changed since it was loaded. Refresh and try again.");
    }
    validations.assertVersion(paycheck.getVersion(), paycheckVersion);
    validations.assertVersion(entry.getVersion(), entryVersion);
    validations.requireActive(paycheck);
    if (entry.getEntryType() != EntryType.BILL) {
      throw new BusinessRuleException("Only Bills can be linked to recurring Bills.");
    }
    List<PaycheckEntry> liveEntries =
        entries.findAllByPaycheckIdAndOwnerIdAndDeletedAtIsNullOrderByPosition(
            paycheck.getId(), ownerId);
    return new LockedEntry(paycheck, entry, liveEntries);
  }

  private void assertAllocation(LockedEntry locked, long nextAmountMinor) {
    List<AllocationLine> proposed =
        locked.liveEntries().stream()
            .map(
                candidate ->
                    new AllocationLine(
                        candidate.getId().equals(locked.entry().getId())
                            ? nextAmountMinor
                            : candidate.getAmountMinor(),
                        candidate.getStatus(),
                        false))
            .toList();
    validations.assertNotOverAllocated(
        responseAssembler.calculate(locked.paycheck().getAmountMinor(), proposed));
  }

  private void requireActive(RecurringBillDefinition definition) {
    if (!definition.isActive()) {
      throw new BusinessRuleException("Activate the recurring Bill before linking it.");
    }
  }

  private void validateOccurrence(int dueDay, LocalDate occurrenceDate) {
    LocalDate expected = occurrencePolicy.occurrence(YearMonth.from(occurrenceDate), dueDay);
    if (!expected.equals(occurrenceDate)) {
      throw new BusinessRuleException("The recurring Bill occurrence date is invalid.");
    }
  }

  private void requireDuplicateConfirmation(
      UUID ownerId,
      UUID currentEntryId,
      UUID definitionId,
      LocalDate occurrenceDate,
      boolean confirmed) {
    if (confirmed) return;
    List<PaycheckEntry> matching =
        entries
            .findAllByOwnerIdAndSourceRecurringBillDefinitionIdAndSourceRecurringOccurrenceDateAndDeletedAtIsNullOrderByPaycheckIdAscPositionAscIdAsc(
                ownerId, definitionId, occurrenceDate)
            .stream()
            .filter(entry -> !entry.getId().equals(currentEntryId))
            .toList();
    if (matching.isEmpty()) return;
    Map<UUID, Paycheck> paycheckById = new LinkedHashMap<>();
    paychecks
        .findAllByIdInAndOwnerId(
            matching.stream().map(PaycheckEntry::getPaycheckId).distinct().toList(), ownerId)
        .forEach(paycheck -> paycheckById.put(paycheck.getId(), paycheck));
    List<Map<String, Object>> assignments = new ArrayList<>();
    for (PaycheckEntry entry : matching) {
      Paycheck paycheck = paycheckById.get(entry.getPaycheckId());
      if (paycheck == null) continue;
      assignments.add(
          Map.of(
              "entryId", entry.getId(),
              "entryStatus", entry.getStatus(),
              "paycheckId", paycheck.getId(),
              "paycheckName", paycheck.getName(),
              "paycheckState", paycheck.getState()));
    }
    if (!assignments.isEmpty()) {
      throw new BusinessRuleException(
          "RECURRING_OCCURRENCE_ALREADY_ASSIGNED",
          "This recurring Bill occurrence is already assigned. Confirm another assignment to continue.",
          Map.of(
              "definitionId", definitionId,
              "occurrenceDate", occurrenceDate,
              "assignments", assignments));
    }
  }

  private record LockedEntry(
      Paycheck paycheck, PaycheckEntry entry, List<PaycheckEntry> liveEntries) {}

  private record EntryLockCandidate(UUID paycheckId, UUID paybackId) {}

  private record RecurringSource(UUID definitionId, LocalDate occurrenceDate) {
    private static RecurringSource from(PaycheckEntry entry) {
      return new RecurringSource(
          entry.getSourceRecurringBillDefinitionId(), entry.getSourceRecurringOccurrenceDate());
    }
  }

  private record RecurringLinkAuditMetadata(
      UUID paycheckId,
      RecurringSource previousSource,
      RecurringSource resultingSource,
      boolean definitionCreated) {}
}
