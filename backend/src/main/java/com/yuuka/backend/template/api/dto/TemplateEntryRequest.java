package com.yuuka.backend.template.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record TemplateEntryRequest(
    @NotNull EntryType entryType,
    @NotBlank @Size(max = 160) String name,
    @PositiveOrZero(message = "Amount must be greater than or equal to $0.00.")
        long defaultAmountMinor,
    EntryPaymentMethod paymentMethod,
    Integer defaultDueOffsetDays,
    @Size(max = 160) String accountName,
    @Size(max = 160) String payee,
    @Size(max = 2000) String notes,
    @PositiveOrZero(message = "Target amount must be greater than or equal to $0.00.")
        Long targetMinor,
    LocalDate targetDate,
    @PositiveOrZero Long version) {}
