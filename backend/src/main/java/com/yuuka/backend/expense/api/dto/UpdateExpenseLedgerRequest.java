package com.yuuka.backend.expense.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateExpenseLedgerRequest(
    @NotBlank @Size(max = 160) String name,
    @Size(max = 2000) String notes,
    @NotNull @PositiveOrZero Long version) {}
