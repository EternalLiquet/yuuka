package com.yuuka.backend.expense.application;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.expense.api.dto.CreateExpenseLedgerItemRequest;
import com.yuuka.backend.expense.api.dto.CreateExpenseLedgerRequest;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerItemResponse;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerResponse;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerSettlementResultResponse;
import com.yuuka.backend.expense.api.dto.SettleExpenseLedgerAsBillRequest;
import com.yuuka.backend.expense.api.dto.SettleExpenseLedgerAsPaybackRequest;
import com.yuuka.backend.expense.api.dto.UpdateExpenseLedgerItemRequest;
import com.yuuka.backend.expense.api.dto.UpdateExpenseLedgerRequest;
import com.yuuka.backend.expense.domain.ExpenseLedger;
import com.yuuka.backend.expense.domain.ExpenseLedgerItem;
import com.yuuka.backend.expense.domain.ExpenseLedgerSettlement;
import com.yuuka.backend.expense.domain.ExpenseLedgerSettlementType;
import com.yuuka.backend.expense.domain.ExpenseLedgerState;
import com.yuuka.backend.expense.infrastructure.JpaExpenseLedgerItemRepository;
import com.yuuka.backend.expense.infrastructure.JpaExpenseLedgerRepository;
import com.yuuka.backend.expense.infrastructure.JpaExpenseLedgerSettlementRepository;
import com.yuuka.backend.payback.api.dto.PaybackResponse;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.api.dto.EntryResponse;
import com.yuuka.backend.paycheck.application.PaycheckService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseLedgerService {
  private final JpaExpenseLedgerRepository ledgers;
  private final JpaExpenseLedgerItemRepository items;
  private final JpaExpenseLedgerSettlementRepository settlements;
  private final ExpenseLedgerResponseAssembler responses;
  private final OwnerLocalDateService ownerLocalDateService;
  private final PaycheckService paycheckService;
  private final PaybackService paybackService;
  private final AuditService auditService;
  private final Clock clock;

  public ExpenseLedgerService(
      JpaExpenseLedgerRepository ledgers,
      JpaExpenseLedgerItemRepository items,
      JpaExpenseLedgerSettlementRepository settlements,
      ExpenseLedgerResponseAssembler responses,
      OwnerLocalDateService ownerLocalDateService,
      PaycheckService paycheckService,
      PaybackService paybackService,
      AuditService auditService,
      Clock clock) {
    this.ledgers = ledgers;
    this.items = items;
    this.settlements = settlements;
    this.responses = responses;
    this.ownerLocalDateService = ownerLocalDateService;
    this.paycheckService = paycheckService;
    this.paybackService = paybackService;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Transactional
  public ExpenseLedgerResponse create(UUID ownerId, CreateExpenseLedgerRequest request) {
    ExpenseLedger ledger =
        ledgers.saveAndFlush(
            new ExpenseLedger(ownerId, request.name().trim(), normalizeOptional(request.notes())));
    ExpenseLedgerResponse response = responses.toResponse(ledger);
    auditService.append(
        ownerId, "EXPENSE_LEDGER", ledger.getId(), "CREATED", null, null, response, null);
    return response;
  }

  @Transactional(readOnly = true)
  public PageResponse<ExpenseLedgerResponse> list(
      UUID ownerId, ExpenseLedgerState state, int page, int size) {
    PageRequest pageable = listPage(page, size);
    Page<ExpenseLedger> result =
        state == null
            ? ledgers.findAllByOwnerIdAndDeletedAtIsNull(ownerId, pageable)
            : ledgers.findAllByOwnerIdAndStateAndDeletedAtIsNull(ownerId, state, pageable);
    return new PageResponse<>(
        responses.toListResponses(ownerId, result.getContent()),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages(),
        result.hasNext());
  }

  @Transactional(readOnly = true)
  public ExpenseLedgerResponse get(UUID ownerId, UUID ledgerId) {
    return responses.toResponse(requireLedger(ownerId, ledgerId));
  }

  @Transactional
  public ExpenseLedgerResponse update(
      UUID ownerId, UUID ledgerId, UpdateExpenseLedgerRequest request) {
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, ledgerId);
    requireOpen(ledger);
    assertVersion(ledger.getVersion(), request.version());
    ExpenseLedgerResponse before = responses.toResponse(ledger);
    ledger.update(request.name().trim(), normalizeOptional(request.notes()));
    ledgers.flush();
    ExpenseLedgerResponse after = responses.toResponse(ledger);
    auditService.append(ownerId, "EXPENSE_LEDGER", ledgerId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public void delete(UUID ownerId, UUID ledgerId, long version) {
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, ledgerId);
    assertVersion(ledger.getVersion(), version);
    if (ledger.getState() == ExpenseLedgerState.SETTLED) {
      throw new BusinessRuleException(
          "Settled Expense Ledgers are historical and cannot be deleted.");
    }
    ExpenseLedgerResponse before = responses.toResponse(ledger);
    ledger.delete(clock.instant());
    ledgers.flush();
    auditService.append(ownerId, "EXPENSE_LEDGER", ledgerId, "DELETED", null, before, null, null);
  }

  @Transactional
  public ExpenseLedgerItemResponse createItem(
      UUID ownerId, UUID ledgerId, CreateExpenseLedgerItemRequest request) {
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, ledgerId);
    requireOpen(ledger);
    LocalDate expenseDate =
        request.expenseDate() == null
            ? ownerLocalDateService.currentDate(ownerId)
            : request.expenseDate();
    validateItem(request.name(), request.merchant(), request.amountMinor(), expenseDate, ownerId);
    MoneyArithmetic.add(responses.totalsFor(ownerId, ledgerId).totalMinor(), request.amountMinor());
    ExpenseLedgerItem item =
        items.saveAndFlush(
            new ExpenseLedgerItem(
                ownerId,
                ledgerId,
                normalizeOptional(request.name()),
                normalizeOptional(request.merchant()),
                request.amountMinor(),
                expenseDate,
                normalizeOptional(request.notes())));
    ledger.touch(clock.instant());
    ledgers.flush();
    ExpenseLedgerItemResponse response = ExpenseLedgerItemResponse.from(item);
    auditService.append(
        ownerId,
        "EXPENSE_LEDGER_ITEM",
        item.getId(),
        "CREATED",
        expenseDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
        null,
        response,
        Map.of("ledgerId", ledgerId));
    return response;
  }

  @Transactional
  public ExpenseLedgerItemResponse updateItem(
      UUID ownerId, UUID itemId, UpdateExpenseLedgerItemRequest request) {
    ExpenseLedgerItem item = requireItemForUpdate(ownerId, itemId);
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, item.getLedgerId());
    requireOpen(ledger);
    assertVersion(item.getVersion(), request.version());
    validateItem(
        request.name(), request.merchant(), request.amountMinor(), request.expenseDate(), ownerId);
    long currentTotal = responses.totalsFor(ownerId, ledger.getId()).totalMinor();
    long totalWithoutItem = MoneyArithmetic.subtract(currentTotal, item.getAmountMinor());
    MoneyArithmetic.add(totalWithoutItem, request.amountMinor());
    ExpenseLedgerItemResponse before = ExpenseLedgerItemResponse.from(item);
    item.update(
        normalizeOptional(request.name()),
        normalizeOptional(request.merchant()),
        request.amountMinor(),
        request.expenseDate(),
        normalizeOptional(request.notes()));
    ledger.touch(clock.instant());
    ledgers.flush();
    items.flush();
    ExpenseLedgerItemResponse after = ExpenseLedgerItemResponse.from(item);
    auditService.append(
        ownerId, "EXPENSE_LEDGER_ITEM", itemId, "UPDATED", null, before, after, null);
    return after;
  }

  @Transactional
  public void deleteItem(UUID ownerId, UUID itemId, long version) {
    ExpenseLedgerItem item = requireItemForUpdate(ownerId, itemId);
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, item.getLedgerId());
    requireOpen(ledger);
    assertVersion(item.getVersion(), version);
    ExpenseLedgerItemResponse before = ExpenseLedgerItemResponse.from(item);
    item.delete(clock.instant());
    ledger.touch(clock.instant());
    ledgers.flush();
    items.flush();
    auditService.append(
        ownerId, "EXPENSE_LEDGER_ITEM", itemId, "DELETED", null, before, null, null);
  }

  @Transactional
  public ExpenseLedgerResponse finalizeLedger(UUID ownerId, UUID ledgerId, long version) {
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, ledgerId);
    requireOpen(ledger);
    assertVersion(ledger.getVersion(), version);
    ExpenseLedgerResponseAssembler.Totals totals = responses.totalsFor(ownerId, ledgerId);
    if (totals.itemCount() == 0 || totals.totalMinor() <= 0) {
      throw new BusinessRuleException(
          "EXPENSE_LEDGER_EMPTY",
          "Add at least one positive expense before finalizing this ledger.",
          Map.of());
    }
    ExpenseLedgerResponse before = responses.toResponse(ledger);
    ledger.finalizeLedger(clock.instant());
    ledgers.flush();
    ExpenseLedgerResponse after = responses.toResponse(ledger);
    auditService.append(
        ownerId, "EXPENSE_LEDGER", ledgerId, "FINALIZED", null, before, after, null);
    return after;
  }

  @Transactional
  public ExpenseLedgerResponse reopen(UUID ownerId, UUID ledgerId, long version) {
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, ledgerId);
    assertVersion(ledger.getVersion(), version);
    if (ledger.getState() != ExpenseLedgerState.FINALIZED) {
      throw new BusinessRuleException(
          "Only a finalized, unsettled Expense Ledger can be reopened.");
    }
    if (settlements.existsByLedgerIdAndOwnerId(ledgerId, ownerId)) {
      throw new BusinessRuleException("Settled Expense Ledgers cannot be reopened.");
    }
    ExpenseLedgerResponse before = responses.toResponse(ledger);
    ledger.reopen(clock.instant());
    ledgers.flush();
    ExpenseLedgerResponse after = responses.toResponse(ledger);
    auditService.append(ownerId, "EXPENSE_LEDGER", ledgerId, "REOPENED", null, before, after, null);
    return after;
  }

  @Transactional
  public ExpenseLedgerSettlementResultResponse settleAsBill(
      UUID ownerId, UUID ledgerId, SettleExpenseLedgerAsBillRequest request) {
    ExpenseLedger ledger =
        requireFinalizedSettlementCandidate(ownerId, ledgerId, request.ledgerVersion());
    ExpenseLedgerResponseAssembler.Totals totals = settlementTotals(ownerId, ledgerId);
    EntryResponse bill =
        paycheckService.createExpenseLedgerSettlementBill(
            ownerId,
            ledgerId,
            request.paycheckId(),
            defaultName(request.name(), ledger),
            totals.totalMinor(),
            request.paymentMethod(),
            request.dueDate(),
            normalizeOptional(request.accountName()),
            normalizeOptional(request.payee()),
            normalizeOptional(request.notes()));
    ExpenseLedgerResponse ledgerResponse =
        recordSettlement(
            ownerId,
            ledger,
            ExpenseLedgerSettlementType.BILL,
            totals.totalMinor(),
            bill.id(),
            bill.paycheckId());
    return new ExpenseLedgerSettlementResultResponse(ledgerResponse, bill, null);
  }

  @Transactional
  public ExpenseLedgerSettlementResultResponse settleAsPayback(
      UUID ownerId, UUID ledgerId, SettleExpenseLedgerAsPaybackRequest request) {
    ExpenseLedger ledger =
        requireFinalizedSettlementCandidate(ownerId, ledgerId, request.ledgerVersion());
    ExpenseLedgerResponseAssembler.Totals totals = settlementTotals(ownerId, ledgerId);
    LocalDate borrowedDate =
        request.borrowedDate() == null ? totals.latestExpenseDate() : request.borrowedDate();
    if (borrowedDate == null) {
      throw new BusinessRuleException("Expense Ledger settlement requires at least one live item.");
    }
    if (borrowedDate.isAfter(ownerLocalDateService.currentDate(ownerId))) {
      throw new BusinessRuleException("Borrowed date cannot be in the future.");
    }
    PaybackResponse payback =
        paybackService.createExpenseLedgerSettlementPayback(
            ownerId,
            ledgerId,
            defaultName(request.name(), ledger),
            totals.totalMinor(),
            borrowedDate,
            normalizeOptional(request.source()),
            normalizeOptional(request.notes()));
    ExpenseLedgerResponse ledgerResponse =
        recordSettlement(
            ownerId,
            ledger,
            ExpenseLedgerSettlementType.PAYBACK,
            totals.totalMinor(),
            payback.id(),
            null);
    return new ExpenseLedgerSettlementResultResponse(ledgerResponse, null, payback);
  }

  public ExpenseLedger requireLedger(UUID ownerId, UUID ledgerId) {
    return ledgers
        .findByIdAndOwnerIdAndDeletedAtIsNull(ledgerId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private ExpenseLedger requireLedgerForUpdate(UUID ownerId, UUID ledgerId) {
    return ledgers
        .findByIdAndOwnerIdForUpdate(ledgerId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private ExpenseLedgerItem requireItemForUpdate(UUID ownerId, UUID itemId) {
    return items
        .findByIdAndOwnerIdForUpdate(itemId, ownerId)
        .orElseThrow(ResourceNotFoundException::new);
  }

  private ExpenseLedger requireFinalizedSettlementCandidate(
      UUID ownerId, UUID ledgerId, long version) {
    ExpenseLedger ledger = requireLedgerForUpdate(ownerId, ledgerId);
    assertVersion(ledger.getVersion(), version);
    if (ledger.getState() != ExpenseLedgerState.FINALIZED) {
      throw new BusinessRuleException("Finalize this Expense Ledger before settling it.");
    }
    if (settlements.existsByLedgerIdAndOwnerId(ledgerId, ownerId)) {
      throw new BusinessRuleException("This Expense Ledger has already been settled.");
    }
    return ledger;
  }

  private ExpenseLedgerResponseAssembler.Totals settlementTotals(UUID ownerId, UUID ledgerId) {
    ExpenseLedgerResponseAssembler.Totals totals = responses.totalsFor(ownerId, ledgerId);
    if (totals.itemCount() == 0 || totals.totalMinor() <= 0) {
      throw new BusinessRuleException("Expense Ledger settlement requires at least one live item.");
    }
    return totals;
  }

  private ExpenseLedgerResponse recordSettlement(
      UUID ownerId,
      ExpenseLedger ledger,
      ExpenseLedgerSettlementType settlementType,
      long amountMinor,
      UUID targetId,
      UUID targetPaycheckId) {
    Instant settledAt = clock.instant();
    ExpenseLedgerResponse before = responses.toResponse(ledger);
    settlements.saveAndFlush(
        new ExpenseLedgerSettlement(
            ownerId,
            ledger.getId(),
            settlementType,
            amountMinor,
            targetId,
            targetPaycheckId,
            settledAt));
    ledger.settle(settledAt);
    ledgers.flush();
    ExpenseLedgerResponse after = responses.toResponse(ledger);
    auditService.append(
        ownerId,
        "EXPENSE_LEDGER",
        ledger.getId(),
        "SETTLED",
        settledAt,
        before,
        after,
        settlementMetadata(settlementType, targetId, targetPaycheckId));
    return after;
  }

  private Map<String, Object> settlementMetadata(
      ExpenseLedgerSettlementType settlementType, UUID targetId, UUID targetPaycheckId) {
    if (targetPaycheckId == null) {
      return Map.of("settlementType", settlementType, "targetId", targetId);
    }
    return Map.of(
        "settlementType", settlementType,
        "targetId", targetId,
        "targetPaycheckId", targetPaycheckId);
  }

  private void requireOpen(ExpenseLedger ledger) {
    if (ledger.getState() != ExpenseLedgerState.OPEN) {
      throw new BusinessRuleException("Reopen this Expense Ledger before changing it.");
    }
  }

  private void validateItem(
      String name, String merchant, long amountMinor, LocalDate expenseDate, UUID ownerId) {
    if (normalizeOptional(name) == null && normalizeOptional(merchant) == null) {
      throw new BusinessRuleException("Enter a name or merchant for this expense.");
    }
    if (amountMinor <= 0) {
      throw new BusinessRuleException("Expense amount must be greater than $0.00.");
    }
    if (expenseDate.isAfter(ownerLocalDateService.currentDate(ownerId))) {
      throw new BusinessRuleException("Expense date cannot be in the future.");
    }
  }

  private String defaultName(String requested, ExpenseLedger ledger) {
    String normalized = normalizeOptional(requested);
    return normalized == null ? ledger.getName() : normalized;
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

  private PageRequest listPage(int requestedPage, int requestedSize) {
    return PageRequest.of(
        Math.max(0, requestedPage),
        Math.min(Math.max(requestedSize, 1), 100),
        Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
  }
}
