package com.yuuka.backend.expense.api.dto;

import com.yuuka.backend.expense.domain.ExpenseLedgerItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseLedgerItemResponse(
    UUID id,
    UUID ledgerId,
    String name,
    String merchant,
    long amountMinor,
    LocalDate expenseDate,
    String notes,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static ExpenseLedgerItemResponse from(ExpenseLedgerItem item) {
    return new ExpenseLedgerItemResponse(
        item.getId(),
        item.getLedgerId(),
        item.getName(),
        item.getMerchant(),
        item.getAmountMinor(),
        item.getExpenseDate(),
        item.getNotes(),
        item.getCreatedAt(),
        item.getUpdatedAt(),
        item.getVersion());
  }
}
