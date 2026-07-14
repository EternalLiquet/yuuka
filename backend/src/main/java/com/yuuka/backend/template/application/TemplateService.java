package com.yuuka.backend.template.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.bucket.application.SpendingBucketPerformanceService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.paycheck.api.dto.CreatePaycheckFromTemplateRequest;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.api.dto.TemplateApplicationEntryRequest;
import com.yuuka.backend.paycheck.domain.AllocationLine;
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
import com.yuuka.backend.template.api.dto.CreateTemplateRequest;
import com.yuuka.backend.template.api.dto.DuplicateTemplateRequest;
import com.yuuka.backend.template.api.dto.ReorderTemplateEntriesRequest;
import com.yuuka.backend.template.api.dto.TemplateEntryRequest;
import com.yuuka.backend.template.api.dto.TemplateEntryResponse;
import com.yuuka.backend.template.api.dto.TemplateResponse;
import com.yuuka.backend.template.api.dto.UpdateTemplateRequest;
import com.yuuka.backend.template.domain.BudgetTemplate;
import com.yuuka.backend.template.domain.TemplateEntry;
import com.yuuka.backend.template.infrastructure.JpaBudgetTemplateRepository;
import com.yuuka.backend.template.infrastructure.JpaTemplateEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateService {
  private final JpaBudgetTemplateRepository templates;
  private final JpaTemplateEntryRepository templateEntries;
  private final JpaPaycheckRepository paychecks;
  private final JpaPaycheckEntryRepository paycheckEntries;
  private final JpaEntryStatusEventRepository statusEvents;
  private final PaycheckCalculator calculator;
  private final SpendingBucketPerformanceService spendingBucketPerformanceService;
  private final OwnerLocalDateService ownerLocalDateService;
  private final AuditService auditService;
  private final Clock clock;

  public TemplateService(
      JpaBudgetTemplateRepository templates,
      JpaTemplateEntryRepository templateEntries,
      JpaPaycheckRepository paychecks,
      JpaPaycheckEntryRepository paycheckEntries,
      JpaEntryStatusEventRepository statusEvents,
      PaycheckCalculator calculator,
      SpendingBucketPerformanceService spendingBucketPerformanceService,
      OwnerLocalDateService ownerLocalDateService,
      AuditService auditService,
      Clock clock) {
    this.templates = templates;
    this.templateEntries = templateEntries;
    this.paychecks = paychecks;
    this.paycheckEntries = paycheckEntries;
    this.statusEvents = statusEvents;
    this.calculator = calculator;
    this.spendingBucketPerformanceService = spendingBucketPerformanceService;
    this.ownerLocalDateService = ownerLocalDateService;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<TemplateResponse> list(UUID ownerId, boolean includeArchived) {
    return templates.findAllByOwnerIdOrderByArchivedAscUpdatedAtDesc(ownerId).stream()
        .filter(template -> includeArchived || !template.isArchived())
        .map(template -> response(ownerId, template))
        .toList();
  }

  @Transactional(readOnly = true)
  public TemplateResponse get(UUID ownerId, UUID templateId) {
    return response(ownerId, requireTemplate(ownerId, templateId));
  }

  @Transactional
  public TemplateResponse create(UUID ownerId, CreateTemplateRequest request) {
    BudgetTemplate template =
        templates.saveAndFlush(
            new BudgetTemplate(
                ownerId, request.name().trim(), normalizeOptional(request.description())));
    List<TemplateEntry> created = new ArrayList<>();
    for (int index = 0; index < request.entries().size(); index++) {
      created.add(
          templateEntries.save(
              fromRequest(ownerId, template.getId(), request.entries().get(index), index)));
    }
    templateEntries.flush();
    TemplateResponse response = TemplateResponse.from(template, created);
    auditService.append(
        ownerId, "TEMPLATE", template.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional
  public TemplateResponse update(UUID ownerId, UUID templateId, UpdateTemplateRequest request) {
    BudgetTemplate template = requireTemplate(ownerId, templateId);
    assertVersion(template.getVersion(), request.version());
    TemplateResponse before = response(ownerId, template);
    template.update(request.name().trim(), normalizeOptional(request.description()));
    templates.flush();
    TemplateResponse after = response(ownerId, template);
    auditService.append(ownerId, "TEMPLATE", templateId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public TemplateResponse duplicate(
      UUID ownerId, UUID templateId, DuplicateTemplateRequest request) {
    BudgetTemplate source = requireTemplate(ownerId, templateId);
    List<TemplateEntry> sourceEntries = findEntries(ownerId, templateId);
    String requestedName = request == null ? null : request.name();
    String name =
        requestedName == null || requestedName.isBlank()
            ? source.getName() + " Copy"
            : requestedName.trim();
    BudgetTemplate copy =
        templates.saveAndFlush(new BudgetTemplate(ownerId, name, source.getDescription()));
    List<TemplateEntry> copied = new ArrayList<>();
    for (TemplateEntry entry : sourceEntries) {
      copied.add(
          templateEntries.save(
              new TemplateEntry(
                  ownerId,
                  copy.getId(),
                  entry.getEntryType(),
                  entry.getName(),
                  entry.getDefaultAmountMinor(),
                  entry.getPosition(),
                  entry.getPaymentMethod(),
                  entry.getDefaultDueOffsetDays(),
                  entry.getAccountName(),
                  entry.getPayee(),
                  entry.getNotes(),
                  entry.getTargetMinor(),
                  entry.getTargetDate())));
    }
    templateEntries.flush();
    TemplateResponse response = TemplateResponse.from(copy, copied);
    auditService.append(
        ownerId,
        "TEMPLATE",
        copy.getId(),
        "DUPLICATED",
        null,
        null,
        response,
        Map.of("sourceTemplateId", source.getId()));
    return response;
  }

  @Transactional
  public TemplateResponse archive(UUID ownerId, UUID templateId, long version) {
    BudgetTemplate template = requireTemplate(ownerId, templateId);
    assertVersion(template.getVersion(), version);
    TemplateResponse before = response(ownerId, template);
    template.archive(clock.instant());
    templates.flush();
    TemplateResponse after = response(ownerId, template);
    auditService.append(ownerId, "TEMPLATE", templateId, "ARCHIVED", null, before, after, null);
    return after;
  }

  @Transactional
  public TemplateResponse restore(UUID ownerId, UUID templateId, long version) {
    BudgetTemplate template = requireTemplate(ownerId, templateId);
    assertVersion(template.getVersion(), version);
    TemplateResponse before = response(ownerId, template);
    template.restore();
    templates.flush();
    TemplateResponse after = response(ownerId, template);
    auditService.append(ownerId, "TEMPLATE", templateId, "RESTORED", null, before, after, null);
    return after;
  }

  @Transactional
  public TemplateEntryResponse addEntry(
      UUID ownerId, UUID templateId, TemplateEntryRequest request) {
    BudgetTemplate template = requireTemplate(ownerId, templateId);
    requireEditable(template);
    TemplateEntry entry =
        templateEntries.saveAndFlush(
            fromRequest(
                ownerId, templateId, request, templateEntries.findMaxPosition(templateId) + 1));
    template.touch(clock.instant());
    TemplateEntryResponse response = TemplateEntryResponse.from(entry);
    auditService.append(
        ownerId, "TEMPLATE_ENTRY", entry.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional
  public TemplateEntryResponse updateEntry(
      UUID ownerId, UUID entryId, TemplateEntryRequest request) {
    TemplateEntry entry = requireEntry(ownerId, entryId);
    BudgetTemplate template = requireTemplate(ownerId, entry.getTemplateId());
    requireEditable(template);
    if (request.version() == null) {
      throw new BusinessRuleException("Template entry version is required.");
    }
    assertVersion(entry.getVersion(), request.version());
    TemplateEntryResponse before = TemplateEntryResponse.from(entry);
    entry.update(
        request.entryType(),
        request.name().trim(),
        request.defaultAmountMinor(),
        paymentMethodForUpdate(entry, request.entryType(), request.paymentMethod()),
        billValue(request.entryType(), request.defaultDueOffsetDays()),
        billValue(request.entryType(), normalizeOptional(request.accountName())),
        billValue(request.entryType(), normalizeOptional(request.payee())),
        normalizeOptional(request.notes()),
        sinkingValue(request.entryType(), request.targetMinor()),
        sinkingValue(request.entryType(), request.targetDate()));
    template.touch(clock.instant());
    templateEntries.flush();
    TemplateEntryResponse after = TemplateEntryResponse.from(entry);
    auditService.append(ownerId, "TEMPLATE_ENTRY", entryId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public void deleteEntry(UUID ownerId, UUID entryId, long version) {
    TemplateEntry entry = requireEntry(ownerId, entryId);
    BudgetTemplate template = requireTemplate(ownerId, entry.getTemplateId());
    requireEditable(template);
    assertVersion(entry.getVersion(), version);
    TemplateEntryResponse before = TemplateEntryResponse.from(entry);
    templateEntries.delete(entry);
    template.touch(clock.instant());
    auditService.append(ownerId, "TEMPLATE_ENTRY", entryId, "DELETED", null, before, null, null);
  }

  @Transactional
  public TemplateResponse reorder(
      UUID ownerId, UUID templateId, ReorderTemplateEntriesRequest request) {
    BudgetTemplate template = requireTemplate(ownerId, templateId);
    requireEditable(template);
    assertVersion(template.getVersion(), request.templateVersion());
    List<TemplateEntry> existing = findEntries(ownerId, templateId);
    Set<UUID> expected = existing.stream().map(TemplateEntry::getId).collect(Collectors.toSet());
    Set<UUID> supplied = new HashSet<>(request.entryIds());
    if (supplied.size() != request.entryIds().size() || !supplied.equals(expected)) {
      throw new BusinessRuleException("Reorder must include every template entry exactly once.");
    }
    Map<UUID, Integer> before =
        existing.stream()
            .collect(Collectors.toMap(TemplateEntry::getId, TemplateEntry::getPosition));
    int offset = existing.size();
    for (int index = 0; index < existing.size(); index++) {
      existing.get(index).moveTo(offset + index);
    }
    templateEntries.saveAllAndFlush(existing);
    Map<UUID, TemplateEntry> byId =
        existing.stream().collect(Collectors.toMap(TemplateEntry::getId, value -> value));
    for (int index = 0; index < request.entryIds().size(); index++) {
      byId.get(request.entryIds().get(index)).moveTo(index);
    }
    templateEntries.saveAllAndFlush(existing);
    template.touch(clock.instant());
    auditService.append(
        ownerId,
        "TEMPLATE",
        templateId,
        "ENTRIES_REORDERED",
        null,
        before,
        request.entryIds(),
        null);
    return response(ownerId, template);
  }

  @Transactional
  public PaycheckResponse createPaycheck(UUID ownerId, CreatePaycheckFromTemplateRequest request) {
    BudgetTemplate template = requireTemplate(ownerId, request.templateId());
    if (template.isArchived()) {
      throw new BusinessRuleException("Restore the template before using it.");
    }
    List<ApplicationEntry> applicationEntries = applicationEntries(ownerId, template, request);
    PaycheckMetrics proposed =
        calculator.calculate(
            request.amountMinor(),
            applicationEntries.stream()
                .map(entry -> new AllocationLine(entry.amountMinor(), EntryStatus.NOT_PAID, false))
                .toList());
    if (proposed.unallocatedMinor() < 0) {
      throw new BusinessRuleException(
          "PAYCHECK_OVER_ALLOCATED",
          "This would over-allocate the paycheck.",
          Map.of("amountMinor", Math.abs(proposed.unallocatedMinor()), "currencyCode", "USD"));
    }

    String paycheckName =
        request.name() == null || request.name().isBlank()
            ? template.getName()
            : request.name().trim();
    Paycheck paycheck =
        paychecks.saveAndFlush(
            new Paycheck(
                ownerId,
                paycheckName,
                normalizeOptional(request.source()),
                request.amountMinor(),
                request.incomeDate(),
                normalizeOptional(request.notes()),
                template.getId()));
    List<PaycheckEntry> copied = new ArrayList<>();
    Instant now = clock.instant();
    for (int index = 0; index < applicationEntries.size(); index++) {
      ApplicationEntry source = applicationEntries.get(index);
      PaycheckEntry entry =
          paycheckEntries.saveAndFlush(
              new PaycheckEntry(
                  ownerId,
                  paycheck.getId(),
                  source.entryType(),
                  source.name(),
                  source.amountMinor(),
                  index,
                  source.paymentMethod(),
                  source.dueDate(),
                  source.accountName(),
                  source.payee(),
                  source.notes(),
                  source.targetMinor(),
                  source.targetDate(),
                  null));
      copied.add(entry);
      statusEvents.save(
          new EntryStatusEvent(
              ownerId,
              entry.getId(),
              null,
              EntryStatus.NOT_PAID,
              now,
              now,
              "Copied from template"));
    }
    LocalDate asOfDate = ownerLocalDateService.currentDate(ownerId);
    PaycheckResponse response =
        PaycheckResponse.from(
            paycheck,
            proposed,
            spendingBucketPerformanceService.paycheckSummary(ownerId, paycheck.getId(), asOfDate),
            copied.stream()
                .map(
                    entry ->
                        EntryResponse.from(
                            entry,
                            entry.getEntryType() == EntryType.SPENDING_BUCKET ? 0L : null,
                            entry.getEntryType() == EntryType.SPENDING_BUCKET
                                ? entry.getAmountMinor()
                                : null,
                            entry.getEntryType() == EntryType.SPENDING_BUCKET ? false : null))
                .toList());
    auditService.append(
        ownerId,
        "PAYCHECK",
        paycheck.getId(),
        "CREATED_FROM_TEMPLATE",
        null,
        null,
        response,
        Map.of("templateId", template.getId()));
    return response;
  }

  private List<ApplicationEntry> applicationEntries(
      UUID ownerId, BudgetTemplate template, CreatePaycheckFromTemplateRequest request) {
    if (request.entries() != null) {
      return request.entries().stream().map(this::fromApplicationRequest).toList();
    }
    return findEntries(ownerId, template.getId()).stream()
        .map(
            entry ->
                new ApplicationEntry(
                    entry.getEntryType(),
                    entry.getName(),
                    entry.getDefaultAmountMinor(),
                    entry.getPaymentMethod(),
                    entry.getDefaultDueOffsetDays() == null
                        ? null
                        : request.incomeDate().plusDays(entry.getDefaultDueOffsetDays()),
                    entry.getAccountName(),
                    entry.getPayee(),
                    entry.getNotes(),
                    entry.getTargetMinor(),
                    entry.getTargetDate()))
        .toList();
  }

  private ApplicationEntry fromApplicationRequest(TemplateApplicationEntryRequest request) {
    return new ApplicationEntry(
        request.entryType(),
        request.name().trim(),
        request.amountMinor(),
        paymentMethodForCreate(request.entryType(), request.paymentMethod()),
        billValue(request.entryType(), request.dueDate()),
        billValue(request.entryType(), normalizeOptional(request.accountName())),
        billValue(request.entryType(), normalizeOptional(request.payee())),
        normalizeOptional(request.notes()),
        sinkingValue(request.entryType(), request.targetMinor()),
        sinkingValue(request.entryType(), request.targetDate()));
  }

  private TemplateEntry fromRequest(
      UUID ownerId, UUID templateId, TemplateEntryRequest request, int position) {
    return new TemplateEntry(
        ownerId,
        templateId,
        request.entryType(),
        request.name().trim(),
        request.defaultAmountMinor(),
        position,
        paymentMethodForCreate(request.entryType(), request.paymentMethod()),
        billValue(request.entryType(), request.defaultDueOffsetDays()),
        billValue(request.entryType(), normalizeOptional(request.accountName())),
        billValue(request.entryType(), normalizeOptional(request.payee())),
        normalizeOptional(request.notes()),
        sinkingValue(request.entryType(), request.targetMinor()),
        sinkingValue(request.entryType(), request.targetDate()));
  }

  private TemplateResponse response(UUID ownerId, BudgetTemplate template) {
    return TemplateResponse.from(template, findEntries(ownerId, template.getId()));
  }

  private List<TemplateEntry> findEntries(UUID ownerId, UUID templateId) {
    return templateEntries.findAllByTemplateIdAndOwnerIdOrderByPosition(templateId, ownerId);
  }

  private BudgetTemplate requireTemplate(UUID ownerId, UUID templateId) {
    return templates
        .findByIdAndOwnerId(templateId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private TemplateEntry requireEntry(UUID ownerId, UUID entryId) {
    return templateEntries
        .findByIdAndOwnerId(entryId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private void requireEditable(BudgetTemplate template) {
    if (template.isArchived()) {
      throw new BusinessRuleException("Restore the template before editing it.");
    }
  }

  private void assertVersion(long actual, long supplied) {
    if (actual != supplied) {
      throw new ConflictException(
          "This record changed since it was loaded. Refresh and try again.");
    }
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private <T> T billValue(EntryType type, T value) {
    return type == EntryType.BILL ? value : null;
  }

  private EntryPaymentMethod paymentMethodForCreate(EntryType type, EntryPaymentMethod requested) {
    if (type != EntryType.BILL) {
      if (requested != null) {
        throw new BusinessRuleException("Only Bills can have a payment method.");
      }
      return null;
    }
    return requested == null ? EntryPaymentMethod.AUTOPAY : requested;
  }

  private EntryPaymentMethod paymentMethodForUpdate(
      TemplateEntry existing, EntryType requestedType, EntryPaymentMethod requested) {
    if (requestedType != EntryType.BILL) {
      if (requested != null) {
        throw new BusinessRuleException("Only Bills can have a payment method.");
      }
      return null;
    }
    if (requested != null) {
      return requested;
    }
    return existing.getEntryType() == EntryType.BILL && existing.getPaymentMethod() != null
        ? existing.getPaymentMethod()
        : EntryPaymentMethod.AUTOPAY;
  }

  private <T> T sinkingValue(EntryType type, T value) {
    return type == EntryType.SINKING_FUND ? value : null;
  }

  private record ApplicationEntry(
      EntryType entryType,
      String name,
      long amountMinor,
      EntryPaymentMethod paymentMethod,
      LocalDate dueDate,
      String accountName,
      String payee,
      String notes,
      Long targetMinor,
      LocalDate targetDate) {}
}
