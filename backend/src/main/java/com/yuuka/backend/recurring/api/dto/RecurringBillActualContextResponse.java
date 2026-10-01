package com.yuuka.backend.recurring.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringBillActualContextResponse(
    UUID definitionId,
    long definitionVersion,
    LocalDate occurrenceDate,
    @Schema(types = {"integer", "null"}) Long occurrenceAmountVersion,
    @Schema(types = {"integer", "null"}) Long actualAmountMinor,
    boolean definitionReviewRequired) {}
