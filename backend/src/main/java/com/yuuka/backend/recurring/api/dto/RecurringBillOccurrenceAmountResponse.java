package com.yuuka.backend.recurring.api.dto;

import com.yuuka.backend.recurring.domain.RecurringBillOccurrenceAmount;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringBillOccurrenceAmountResponse(
    UUID definitionId,
    LocalDate occurrenceDate,
    long amountMinor,
    long version,
    Instant createdAt,
    Instant updatedAt) {
  public static RecurringBillOccurrenceAmountResponse from(RecurringBillOccurrenceAmount amount) {
    return new RecurringBillOccurrenceAmountResponse(
        amount.getDefinitionId(),
        amount.getOccurrenceDate(),
        amount.getAmountMinor(),
        amount.getVersion(),
        amount.getCreatedAt(),
        amount.getUpdatedAt());
  }
}
