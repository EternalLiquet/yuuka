package com.yuuka.backend.recurring.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.UUID;

public record LinkRecurringBillRequest(
    @PositiveOrZero long entryVersion,
    @PositiveOrZero long paycheckVersion,
    @NotNull UUID definitionId,
    @PositiveOrZero long definitionVersion,
    @NotNull LocalDate occurrenceDate,
    boolean confirmDuplicateOccurrence) {}
