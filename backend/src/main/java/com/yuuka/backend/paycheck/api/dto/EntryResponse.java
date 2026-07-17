package com.yuuka.backend.paycheck.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EntryResponse(
    UUID id,
    UUID paycheckId,
    UUID paybackId,
    EntryType entryType,
    EntryPaymentMethod paymentMethod,
    String name,
    long amountMinor,
    EntryStatus status,
    int position,
    LocalDate dueDate,
    String accountName,
    String payee,
    String notes,
    Long targetMinor,
    LocalDate targetDate,
    UUID sourceRecurringBillDefinitionId,
    LocalDate sourceRecurringOccurrenceDate,
    Long spentMinor,
    Long remainingMinor,
    Boolean overBudget,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static EntryResponse from(
      PaycheckEntry entry, Long spentMinor, Long remainingMinor, Boolean overBudget) {
    return new EntryResponse(
        entry.getId(),
        entry.getPaycheckId(),
        entry.getPaybackId(),
        entry.getEntryType(),
        entry.getPaymentMethod(),
        entry.getName(),
        entry.getAmountMinor(),
        entry.getStatus(),
        entry.getPosition(),
        entry.getDueDate(),
        entry.getAccountName(),
        entry.getPayee(),
        entry.getNotes(),
        entry.getTargetMinor(),
        entry.getTargetDate(),
        entry.getSourceRecurringBillDefinitionId(),
        entry.getSourceRecurringOccurrenceDate(),
        spentMinor,
        remainingMinor,
        overBudget,
        entry.getCreatedAt(),
        entry.getUpdatedAt(),
        entry.getVersion());
  }
}
