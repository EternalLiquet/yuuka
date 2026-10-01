package com.yuuka.backend.recurring.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.UUID;

public record RecordRecurringBillActualRequest(
    @NotNull UUID definitionId,
    @NotNull @PositiveOrZero Long definitionVersion,
    @NotNull LocalDate occurrenceDate,
    @NotNull @PositiveOrZero Long entryVersion,
    @NotNull @PositiveOrZero Long paycheckVersion,
    @Schema(types = {"integer", "null"}) @PositiveOrZero Long occurrenceAmountVersion,
    @NotNull @PositiveOrZero(message = "Amount must be greater than or equal to $0.00.")
        Long amountMinor,
    boolean confirmDefinitionChanges) {}
