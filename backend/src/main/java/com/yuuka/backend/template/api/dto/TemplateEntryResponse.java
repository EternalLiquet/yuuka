package com.yuuka.backend.template.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.template.domain.TemplateEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TemplateEntryResponse(
    UUID id,
    EntryType entryType,
    EntryPaymentMethod paymentMethod,
    String name,
    long defaultAmountMinor,
    int position,
    Integer defaultDueOffsetDays,
    String accountName,
    String payee,
    String notes,
    Long targetMinor,
    LocalDate targetDate,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static TemplateEntryResponse from(TemplateEntry entry) {
    return new TemplateEntryResponse(
        entry.getId(),
        entry.getEntryType(),
        entry.getPaymentMethod(),
        entry.getName(),
        entry.getDefaultAmountMinor(),
        entry.getPosition(),
        entry.getDefaultDueOffsetDays(),
        entry.getAccountName(),
        entry.getPayee(),
        entry.getNotes(),
        entry.getTargetMinor(),
        entry.getTargetDate(),
        entry.getCreatedAt(),
        entry.getUpdatedAt(),
        entry.getVersion());
  }
}
