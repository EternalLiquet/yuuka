package com.yuuka.backend.expense.application;

import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerItemResponse;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerResponse;
import com.yuuka.backend.expense.api.dto.ExpenseLedgerSettlementResponse;
import com.yuuka.backend.expense.domain.ExpenseLedger;
import com.yuuka.backend.expense.domain.ExpenseLedgerSettlement;
import com.yuuka.backend.expense.infrastructure.ExpenseLedgerTotalsProjection;
import com.yuuka.backend.expense.infrastructure.JpaExpenseLedgerItemRepository;
import com.yuuka.backend.expense.infrastructure.JpaExpenseLedgerSettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class ExpenseLedgerResponseAssembler {
  private static final Totals ZERO = new Totals(0, 0, null);

  private final JpaExpenseLedgerItemRepository items;
  private final JpaExpenseLedgerSettlementRepository settlements;

  ExpenseLedgerResponseAssembler(
      JpaExpenseLedgerItemRepository items, JpaExpenseLedgerSettlementRepository settlements) {
    this.items = items;
    this.settlements = settlements;
  }

  ExpenseLedgerResponse toResponse(ExpenseLedger ledger) {
    Totals totals = totalsFor(ledger.getOwnerId(), ledger.getId());
    List<ExpenseLedgerItemResponse> itemResponses =
        items
            .findAllByLedgerIdAndOwnerIdAndDeletedAtIsNullOrderByExpenseDateDescCreatedAtDescIdDesc(
                ledger.getId(), ledger.getOwnerId())
            .stream()
            .map(ExpenseLedgerItemResponse::from)
            .toList();
    ExpenseLedgerSettlementResponse settlement =
        settlements
            .findByLedgerIdAndOwnerId(ledger.getId(), ledger.getOwnerId())
            .map(ExpenseLedgerSettlementResponse::from)
            .orElse(null);
    return ExpenseLedgerResponse.from(
        ledger,
        totals.totalMinor(),
        totals.itemCount(),
        totals.latestExpenseDate(),
        settlement,
        itemResponses);
  }

  List<ExpenseLedgerResponse> toListResponses(UUID ownerId, List<ExpenseLedger> ledgers) {
    if (ledgers.isEmpty()) {
      return List.of();
    }
    List<UUID> ledgerIds = ledgers.stream().map(ExpenseLedger::getId).toList();
    Map<UUID, Totals> totalsByLedger = totalsFor(ownerId, ledgerIds);
    Map<UUID, ExpenseLedgerSettlement> settlementByLedger =
        settlements.findAllByLedgerIdInAndOwnerId(ledgerIds, ownerId).stream()
            .collect(Collectors.toMap(ExpenseLedgerSettlement::getLedgerId, Function.identity()));
    return ledgers.stream()
        .map(
            ledger -> {
              Totals totals = totalsByLedger.getOrDefault(ledger.getId(), ZERO);
              ExpenseLedgerSettlement settlement = settlementByLedger.get(ledger.getId());
              return ExpenseLedgerResponse.from(
                  ledger,
                  totals.totalMinor(),
                  totals.itemCount(),
                  totals.latestExpenseDate(),
                  settlement == null ? null : ExpenseLedgerSettlementResponse.from(settlement),
                  List.of());
            })
        .toList();
  }

  Totals totalsFor(UUID ownerId, UUID ledgerId) {
    return items.totalsByLedgerId(ownerId, ledgerId).map(this::fromProjection).orElse(ZERO);
  }

  private Map<UUID, Totals> totalsFor(UUID ownerId, Collection<UUID> ledgerIds) {
    return items.totalsByLedgerIds(ownerId, ledgerIds).stream()
        .collect(
            Collectors.toMap(ExpenseLedgerTotalsProjection::getLedgerId, this::fromProjection));
  }

  private Totals fromProjection(ExpenseLedgerTotalsProjection projection) {
    BigDecimal total = projection.getTotalMinor();
    return new Totals(
        MoneyArithmetic.toLongExact(total),
        projection.getItemCount(),
        projection.getLatestExpenseDate());
  }

  record Totals(long totalMinor, long itemCount, LocalDate latestExpenseDate) {}
}
