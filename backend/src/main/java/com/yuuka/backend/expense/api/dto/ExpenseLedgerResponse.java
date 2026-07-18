package com.yuuka.backend.expense.api.dto;

import com.yuuka.backend.expense.domain.ExpenseLedger;
import com.yuuka.backend.expense.domain.ExpenseLedgerState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExpenseLedgerResponse(
    UUID id,
    String name,
    String notes,
    ExpenseLedgerState state,
    long totalMinor,
    long itemCount,
    LocalDate latestExpenseDate,
    ExpenseLedgerSettlementResponse settlement,
    List<ExpenseLedgerItemResponse> items,
    Instant finalizedAt,
    Instant reopenedAt,
    Instant settledAt,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static ExpenseLedgerResponse from(
      ExpenseLedger ledger,
      long totalMinor,
      long itemCount,
      LocalDate latestExpenseDate,
      ExpenseLedgerSettlementResponse settlement,
      List<ExpenseLedgerItemResponse> items) {
    return new ExpenseLedgerResponse(
        ledger.getId(),
        ledger.getName(),
        ledger.getNotes(),
        ledger.getState(),
        totalMinor,
        itemCount,
        latestExpenseDate,
        settlement,
        items,
        ledger.getFinalizedAt(),
        ledger.getReopenedAt(),
        ledger.getSettledAt(),
        ledger.getCreatedAt(),
        ledger.getUpdatedAt(),
        ledger.getVersion());
  }
}
