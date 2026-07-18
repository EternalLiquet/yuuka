package com.yuuka.backend.expense.api.dto;

import com.yuuka.backend.expense.domain.ExpenseLedgerSettlement;
import com.yuuka.backend.expense.domain.ExpenseLedgerSettlementType;
import java.time.Instant;
import java.util.UUID;

public record ExpenseLedgerSettlementResponse(
    UUID id,
    UUID ledgerId,
    ExpenseLedgerSettlementType settlementType,
    long settlementAmountMinor,
    UUID targetId,
    UUID targetPaycheckId,
    Instant settledAt,
    Instant createdAt) {
  public static ExpenseLedgerSettlementResponse from(ExpenseLedgerSettlement settlement) {
    return new ExpenseLedgerSettlementResponse(
        settlement.getId(),
        settlement.getLedgerId(),
        settlement.getSettlementType(),
        settlement.getSettlementAmountMinor(),
        settlement.getTargetId(),
        settlement.getTargetPaycheckId(),
        settlement.getSettledAt(),
        settlement.getCreatedAt());
  }
}
