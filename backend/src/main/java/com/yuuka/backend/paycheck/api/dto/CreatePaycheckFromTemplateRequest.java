package com.yuuka.backend.paycheck.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreatePaycheckFromTemplateRequest(
    @NotNull UUID templateId,
    @Size(max = 120) String name,
    @PositiveOrZero long amountMinor,
    @NotNull LocalDate incomeDate,
    @Size(max = 160) String source,
    @Size(max = 2000) String notes,
    List<@Valid TemplateApplicationEntryRequest> entries) {
  public CreatePaycheckFromTemplateRequest {
    entries = entries == null ? null : List.copyOf(entries);
  }
}
