package com.yuuka.backend.recurring.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.recurring.domain.RecurringBillDefinition;
import com.yuuka.backend.recurring.domain.RecurringBillRecurrenceType;
import java.time.Instant;
import java.util.UUID;

public record RecurringBillResponse(
    UUID id,
    String name,
    long typicalAmountMinor,
    EntryPaymentMethod paymentMethod,
    RecurringBillRecurrenceType recurrenceType,
    int dueDay,
    String accountName,
    String payee,
    String notes,
    boolean active,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static RecurringBillResponse from(RecurringBillDefinition definition) {
    return new RecurringBillResponse(
        definition.getId(),
        definition.getName(),
        definition.getTypicalAmountMinor(),
        definition.getPaymentMethod(),
        definition.getRecurrenceType(),
        definition.getDueDay(),
        definition.getAccountName(),
        definition.getPayee(),
        definition.getNotes(),
        definition.isActive(),
        definition.getCreatedAt(),
        definition.getUpdatedAt(),
        definition.getVersion());
  }
}
