package com.yuuka.backend.expense.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record SettleExpenseLedgerAsPaybackRequest(
    @NotNull @PositiveOrZero Long ledgerVersion,
    @Size(max = 160) String name,
    LocalDate borrowedDate,
    @Size(max = 160) String source,
    @Size(max = 2000) String notes) {}
