package com.yuuka.backend.expense.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateExpenseLedgerItemRequest(
    @Size(max = 160) String name,
    @Size(max = 160) String merchant,
    @NotNull(message = "Enter an amount.")
        @Positive(message = "Expense amount must be greater than $0.00.")
        Long amountMinor,
    LocalDate expenseDate,
    @Size(max = 2000) String notes) {}
