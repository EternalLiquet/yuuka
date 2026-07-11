package com.yuuka.backend.paycheck.api.dto;

import com.yuuka.backend.paycheck.domain.EntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record TemplateApplicationEntryRequest(
    @NotNull EntryType entryType,
    @NotBlank @Size(max = 160) String name,
    @PositiveOrZero long amountMinor,
    LocalDate dueDate,
    @Size(max = 160) String accountName,
    @Size(max = 160) String payee,
    @Size(max = 2000) String notes,
    @PositiveOrZero Long targetMinor,
    LocalDate targetDate) {}
