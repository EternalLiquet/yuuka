package com.yuuka.backend.recurring.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    boolean updateTypicalAmount,
    boolean saveOccurrenceAmount,
    @Schema(types = {"integer", "null"}) @PositiveOrZero Long occurrenceAmountVersion) {}
