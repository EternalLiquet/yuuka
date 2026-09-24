package com.yuuka.backend.recurring.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateRecurringBillOccurrenceAmountRequest(
    @NotNull(message = "Enter an amount.")
        @PositiveOrZero(message = "Amount must be greater than or equal to $0.00.")
        Long amountMinor,
    @Schema(types = {"integer", "null"}) @PositiveOrZero Long version) {}
