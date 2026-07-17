package com.yuuka.backend.recurring.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateRecurringBillRequest(
    @NotBlank @Size(max = 160) String name,
    @NotNull(message = "Enter a typical amount.")
        @PositiveOrZero(message = "Typical amount must be greater than or equal to $0.00.")
        Long typicalAmountMinor,
    EntryPaymentMethod paymentMethod,
    @Min(1) @Max(31) int dueDay,
    @Size(max = 160) String accountName,
    @Size(max = 160) String payee,
    @Size(max = 2000) String notes,
    @PositiveOrZero long version) {}
