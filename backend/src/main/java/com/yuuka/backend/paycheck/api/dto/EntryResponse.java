package com.yuuka.backend.paycheck.api.dto;

import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EntryResponse(
    UUID id,
    UUID paycheckId,
    EntryType entryType,
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
        entry.getEntryType(),
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
        spentMinor,
        remainingMinor,
        overBudget,
        entry.getCreatedAt(),
        entry.getUpdatedAt(),
        entry.getVersion());
  }
}
