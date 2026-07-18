package com.yuuka.backend.recurring.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.application.PaycheckService;
import com.yuuka.backend.paycheck.domain.AllocationLine;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryStatusEvent;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckCalculator;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.infrastructure.JpaEntryStatusEventRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.recurring.api.dto.CreateRecurringBillRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportItemRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportSummaryResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillListResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillOccurrenceResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillResponse;
import com.yuuka.backend.recurring.api.dto.RecurringBillTimelineResponse;
import com.yuuka.backend.recurring.api.dto.UpdateRecurringBillRequest;
import com.yuuka.backend.recurring.domain.MonthlyOccurrencePolicy;
import com.yuuka.backend.recurring.domain.RecurringBillDefinition;
import com.yuuka.backend.recurring.domain.RecurringBillStatusFilter;
import com.yuuka.backend.recurring.infrastructure.JpaRecurringBillDefinitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringBillService {
  private static final long MAX_TIMELINE_DAYS = 366;

  private final JpaRecurringBillDefinitionRepository definitions;
  private final JpaPaycheckRepository paychecks;
  private final JpaPaycheckEntryRepository entries;
  private final JpaEntryStatusEventRepository statusEvents;
  private final PaycheckCalculator paycheckCalculator;
  private final PaycheckService paycheckService;
  private final MonthlyOccurrencePolicy occurrencePolicy;
  private final AuditService auditService;
  private final Clock clock;

  public RecurringBillService(
      JpaRecurringBillDefinitionRepository definitions,
      JpaPaycheckRepository paychecks,
      JpaPaycheckEntryRepository entries,
      JpaEntryStatusEventRepository statusEvents,
      PaycheckCalculator paycheckCalculator,
      PaycheckService paycheckService,
      MonthlyOccurrencePolicy occurrencePolicy,
      AuditService auditService,
      Clock clock) {
    this.definitions = definitions;
    this.paychecks = paychecks;
    this.entries = entries;
    this.statusEvents = statusEvents;
    this.paycheckCalculator = paycheckCalculator;
    this.paycheckService = paycheckService;
    this.occurrencePolicy = occurrencePolicy;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional
  public RecurringBillResponse create(UUID ownerId, CreateRecurringBillRequest request) {
    RecurringBillDefinition definition =
        definitions.saveAndFlush(
            new RecurringBillDefinition(
                ownerId,
                request.name().trim(),
                request.typicalAmountMinor(),
                paymentMethod(request.paymentMethod()),
                request.dueDay(),
                normalizeOptional(request.accountName()),
                normalizeOptional(request.payee()),
                normalizeOptional(request.notes())));
    RecurringBillResponse response = RecurringBillResponse.from(definition);
    auditService.append(
        ownerId,
        "RECURRING_BILL_DEFINITION",
        definition.getId(),
        "CREATED",
        null,
        null,
        response,
        null);
    return response;
  }

  @Transactional(readOnly = true)
  public RecurringBillResponse get(UUID ownerId, UUID definitionId) {
    return RecurringBillResponse.from(requireDefinition(ownerId, definitionId));
  }

  @Transactional(readOnly = true)
  public RecurringBillListResponse list(
      UUID ownerId, RecurringBillStatusFilter status, String search) {
    String query = normalizeOptional(search);
    if (query != null) query = query.toLowerCase(Locale.ROOT);
    final String normalizedQuery = query;
    return new RecurringBillListResponse(
        definitions.findAllByOwnerIdAndDeletedAtIsNullOrderByDueDayAscNameAscIdAsc(ownerId).stream()
            .filter(
                definition ->
                    status == RecurringBillStatusFilter.ALL
                        || (status == RecurringBillStatusFilter.ACTIVE && definition.isActive())
                        || (status == RecurringBillStatusFilter.INACTIVE && !definition.isActive()))
            .filter(definition -> matches(definition, normalizedQuery))
            .map(RecurringBillResponse::from)
            .toList());
  }

  @Transactional
  public RecurringBillResponse update(
      UUID ownerId, UUID definitionId, UpdateRecurringBillRequest request) {
    RecurringBillDefinition definition = requireDefinition(ownerId, definitionId);
    assertVersion(definition.getVersion(), request.version());
    RecurringBillResponse before = RecurringBillResponse.from(definition);
    definition.update(
        request.name().trim(),
        request.typicalAmountMinor(),
        paymentMethod(request.paymentMethod()),
        request.dueDay(),
        normalizeOptional(request.accountName()),
        normalizeOptional(request.payee()),
        normalizeOptional(request.notes()));
    RecurringBillResponse after = RecurringBillResponse.from(definitions.saveAndFlush(definition));
    auditService.append(
        ownerId, "RECURRING_BILL_DEFINITION", definitionId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public RecurringBillResponse activate(UUID ownerId, UUID definitionId, long version) {
    return changeActive(ownerId, definitionId, version, true);
  }

  @Transactional
  public RecurringBillResponse deactivate(UUID ownerId, UUID definitionId, long version) {
    return changeActive(ownerId, definitionId, version, false);
  }

  @Transactional
  public void delete(UUID ownerId, UUID definitionId, long version) {
    RecurringBillDefinition definition = requireDefinition(ownerId, definitionId);
    assertVersion(definition.getVersion(), version);
    RecurringBillResponse before = RecurringBillResponse.from(definition);
    definition.delete(clock.instant());
    definitions.saveAndFlush(definition);
    auditService.append(
        ownerId, "RECURRING_BILL_DEFINITION", definitionId, "DELETED", null, before, null, null);
  }

  @Transactional(readOnly = true)
  public RecurringBillTimelineResponse timeline(UUID ownerId, LocalDate from, LocalDate through) {
    validateTimelineRange(from, through);
    List<RecurringBillDefinition> active =
        definitions.findAllByOwnerIdAndActiveTrueAndDeletedAtIsNullOrderByDueDayAscNameAscIdAsc(
            ownerId);
    Set<UUID> definitionIds =
        active.stream()
            .map(RecurringBillDefinition::getId)
            .collect(HashSet::new, Set::add, Set::addAll);
    List<PaycheckEntry> importedEntries =
        definitionIds.isEmpty()
            ? List.of()
            : entries.findRecurringImports(ownerId, definitionIds, from, through);
    Set<UUID> paycheckIds =
        importedEntries.stream()
            .map(PaycheckEntry::getPaycheckId)
            .collect(HashSet::new, Set::add, Set::addAll);
    Map<UUID, Paycheck> paycheckById = new HashMap<>();
    if (!paycheckIds.isEmpty()) {
      paychecks
          .findAllByIdInAndOwnerId(paycheckIds, ownerId)
          .forEach(paycheck -> paycheckById.put(paycheck.getId(), paycheck));
    }
    Map<OccurrenceKey, List<RecurringBillImportSummaryResponse>> imports = new HashMap<>();
    for (PaycheckEntry entry : importedEntries) {
      Paycheck paycheck = paycheckById.get(entry.getPaycheckId());
      if (paycheck == null) continue;
      OccurrenceKey key =
          new OccurrenceKey(
              entry.getSourceRecurringBillDefinitionId(), entry.getSourceRecurringOccurrenceDate());
      imports
          .computeIfAbsent(key, ignored -> new ArrayList<>())
          .add(
              new RecurringBillImportSummaryResponse(
                  entry.getId(), paycheck.getId(), paycheck.getName(), entry.getStatus()));
    }
    Comparator<RecurringBillImportSummaryResponse> importOrder =
        Comparator.comparing(
                RecurringBillImportSummaryResponse::paycheckName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(RecurringBillImportSummaryResponse::entryId);
    imports.values().forEach(items -> items.sort(importOrder));

    List<RecurringBillOccurrenceResponse> items = new ArrayList<>();
    for (RecurringBillDefinition definition : active) {
      for (LocalDate date : occurrencePolicy.occurrences(from, through, definition.getDueDay())) {
        List<RecurringBillImportSummaryResponse> matching =
            imports.getOrDefault(new OccurrenceKey(definition.getId(), date), List.of());
        items.add(
            new RecurringBillOccurrenceResponse(
                definition.getId(),
                definition.getVersion(),
                date,
                definition.getName(),
                definition.getTypicalAmountMinor(),
                definition.getPaymentMethod(),
                definition.getAccountName(),
                definition.getPayee(),
                definition.getNotes(),
                matching.size(),
                matching));
      }
    }
    items.sort(
        Comparator.comparing(RecurringBillOccurrenceResponse::occurrenceDate)
            .thenComparing(RecurringBillOccurrenceResponse::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(RecurringBillOccurrenceResponse::definitionId));
    return new RecurringBillTimelineResponse(from, through, items);
  }

  @Transactional
  public PaycheckResponse importIntoPaycheck(
      UUID ownerId, UUID paycheckId, RecurringBillImportRequest request) {
    Paycheck paycheck =
        paychecks
            .findByIdAndOwnerIdForUpdate(paycheckId, ownerId)
            .orElseThrow(ResourceNotFoundException::new);
    if (paycheck.getState() != PaycheckState.ACTIVE) {
      throw new BusinessRuleException("Reopen the paycheck before changing it.");
    }
    assertVersion(paycheck.getVersion(), request.paycheckVersion());
    validateTypicalAmountUpdates(request.items());

    List<PaycheckEntry> liveEntries =
        entries.findAllByPaycheckIdAndOwnerIdAndDeletedAtIsNullOrderByPosition(paycheckId, ownerId);
    List<AllocationLine> proposed =
        new ArrayList<>(
            liveEntries.stream()
                .map(entry -> new AllocationLine(entry.getAmountMinor(), entry.getStatus(), false))
                .toList());
    request
        .items()
        .forEach(
            item ->
                proposed.add(new AllocationLine(item.amountMinor(), EntryStatus.NOT_PAID, false)));
    var metrics = paycheckCalculator.calculate(paycheck.getAmountMinor(), proposed);
    if (metrics.unallocatedMinor() < 0) {
      throw new BusinessRuleException(
          "PAYCHECK_OVER_ALLOCATED",
          "This would over-allocate the paycheck.",
          Map.of("amountMinor", Math.abs(metrics.unallocatedMinor()), "currencyCode", "USD"));
    }

    Map<UUID, RecurringBillDefinition> loaded = new HashMap<>();
    request.items().stream()
        .map(RecurringBillImportItemRequest::definitionId)
        .distinct()
        .sorted()
        .forEach(
            definitionId ->
                loaded.put(
                    definitionId,
                    definitions
                        .findByIdAndOwnerIdForUpdate(definitionId, ownerId)
                        .orElseThrow(ResourceNotFoundException::new)));
    for (RecurringBillImportItemRequest item : request.items()) {
      RecurringBillDefinition definition = loaded.get(item.definitionId());
      if (!definition.isActive()) {
        throw new BusinessRuleException("Activate the recurring Bill before importing it.");
      }
      LocalDate expected =
          occurrencePolicy.occurrence(
              YearMonth.from(item.occurrenceDate()), definition.getDueDay());
      if (!expected.equals(item.occurrenceDate())) {
        throw new BusinessRuleException("The recurring Bill occurrence date is invalid.");
      }
      assertVersion(definition.getVersion(), item.definitionVersion());
    }

    Instant now = clock.instant();
    int position = entries.findMaxLivePosition(paycheckId) + 1;
    List<PaycheckEntry> created = new ArrayList<>();
    for (RecurringBillImportItemRequest item : request.items()) {
      RecurringBillDefinition definition = loaded.get(item.definitionId());
      if (item.updateTypicalAmount()) {
        RecurringBillResponse before = RecurringBillResponse.from(definition);
        definition.updateTypicalAmount(item.amountMinor());
        RecurringBillResponse after =
            RecurringBillResponse.from(definitions.saveAndFlush(definition));
        auditService.append(
            ownerId,
            "RECURRING_BILL_DEFINITION",
            definition.getId(),
            "TYPICAL_AMOUNT_UPDATED_DURING_IMPORT",
            null,
            before,
            after,
            Map.of("paycheckId", paycheckId));
      }
      PaycheckEntry entry =
          new PaycheckEntry(
              ownerId,
              paycheckId,
              EntryType.BILL,
              definition.getName(),
              item.amountMinor(),
              position++,
              definition.getPaymentMethod(),
              item.occurrenceDate(),
              definition.getAccountName(),
              definition.getPayee(),
              definition.getNotes(),
              null,
              null,
              null,
              null);
      entry.setRecurringSource(definition.getId(), item.occurrenceDate());
      entry = entries.saveAndFlush(entry);
      created.add(entry);
      statusEvents.save(
          new EntryStatusEvent(
              ownerId,
              entry.getId(),
              null,
              EntryStatus.NOT_PAID,
              now,
              now,
              "Imported recurring Bill"));
      auditService.append(
          ownerId,
          "PAYCHECK_ENTRY",
          entry.getId(),
          "RECURRING_BILL_IMPORTED",
          null,
          null,
          paycheckService.toEntryResponse(entry),
          Map.of("paycheckId", paycheckId, "definitionId", definition.getId()));
    }
    paycheck.touch(now);
    paychecks.saveAndFlush(paycheck);
    List<PaycheckEntry> allEntries = new ArrayList<>(liveEntries);
    allEntries.addAll(created);
    return paycheckService.toResponse(paycheck, allEntries);
  }

  private void validateTypicalAmountUpdates(List<RecurringBillImportItemRequest> items) {
    Set<UUID> definitionsWithTypicalAmountUpdate = new HashSet<>();
    for (RecurringBillImportItemRequest item : items) {
      if (item.updateTypicalAmount()
          && !definitionsWithTypicalAmountUpdate.add(item.definitionId())) {
        throw new BusinessRuleException(
            "Choose Update typical amount for at most one occurrence of each recurring Bill.");
      }
    }
  }

  private RecurringBillResponse changeActive(
      UUID ownerId, UUID definitionId, long version, boolean active) {
    RecurringBillDefinition definition = requireDefinition(ownerId, definitionId);
    assertVersion(definition.getVersion(), version);
    RecurringBillResponse before = RecurringBillResponse.from(definition);
    Consumer<RecurringBillDefinition> change =
        active ? RecurringBillDefinition::activate : RecurringBillDefinition::deactivate;
    change.accept(definition);
    RecurringBillResponse after = RecurringBillResponse.from(definitions.saveAndFlush(definition));
    auditService.append(
        ownerId,
        "RECURRING_BILL_DEFINITION",
        definitionId,
        active ? "ACTIVATED" : "DEACTIVATED",
        null,
        before,
        after,
        null);
    return after;
  }

  private RecurringBillDefinition requireDefinition(UUID ownerId, UUID definitionId) {
    return definitions
        .findByIdAndOwnerIdAndDeletedAtIsNull(definitionId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private void validateTimelineRange(LocalDate from, LocalDate through) {
    if (through.isBefore(from)) {
      throw new BusinessRuleException("Timeline end must not be before its start.");
    }
    if (ChronoUnit.DAYS.between(from, through) > MAX_TIMELINE_DAYS) {
      throw new BusinessRuleException("Recurring Bill timeline ranges cannot exceed 366 days.");
    }
  }

  private boolean matches(RecurringBillDefinition definition, String query) {
    if (query == null) return true;
    return contains(definition.getName(), query)
        || contains(definition.getPayee(), query)
        || contains(definition.getAccountName(), query);
  }

  private boolean contains(String value, String query) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(query);
  }

  private EntryPaymentMethod paymentMethod(EntryPaymentMethod value) {
    return value == null ? EntryPaymentMethod.AUTOPAY : value;
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private void assertVersion(long actual, long supplied) {
    if (actual != supplied) {
      throw new ConflictException(
          "This record changed since it was loaded. Refresh and try again.");
    }
  }

  private record OccurrenceKey(UUID definitionId, LocalDate occurrenceDate) {}
}
