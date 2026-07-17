package com.yuuka.backend.template.api.dto;

import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.template.domain.BudgetTemplate;
import com.yuuka.backend.template.domain.TemplateEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TemplateResponse(
    UUID id,
    String name,
    String description,
    boolean archived,
    int entryCount,
    long defaultTotalMinor,
    List<TemplateEntryResponse> entries,
    Instant createdAt,
    Instant updatedAt,
    Instant archivedAt,
    long version) {
  public static TemplateResponse from(
      BudgetTemplate template, List<TemplateEntry> templateEntries) {
    return new TemplateResponse(
        template.getId(),
        template.getName(),
        template.getDescription(),
        template.isArchived(),
        templateEntries.size(),
        MoneyArithmetic.sum(
            templateEntries.stream().mapToLong(TemplateEntry::getDefaultAmountMinor)),
        templateEntries.stream().map(TemplateEntryResponse::from).toList(),
        template.getCreatedAt(),
        template.getUpdatedAt(),
        template.getArchivedAt(),
        template.getVersion());
  }
}
