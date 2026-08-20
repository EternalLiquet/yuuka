package com.yuuka.backend.recurring.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.UUID;

public record LinkRecurringBillRequest(
    @NotNull @PositiveOrZero Long entryVersion,
    @NotNull @PositiveOrZero Long paycheckVersion,
    @NotNull UUID definitionId,
    @NotNull @PositiveOrZero Long definitionVersion,
    @NotNull LocalDate occurrenceDate,
    boolean confirmDuplicateOccurrence) {}
