package com.yuuka.backend.recurring.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringBillImportItemRequest(
    @NotNull UUID definitionId,
    @PositiveOrZero long definitionVersion,
    @NotNull LocalDate occurrenceDate,
    @NotNull(message = "Enter an amount.")
        @PositiveOrZero(message = "Amount must be greater than or equal to $0.00.")
        Long amountMinor,
    boolean updateTypicalAmount) {}
