package com.yuuka.backend.recurring.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateRecurringBillFromEntryRequest(
    @NotNull @PositiveOrZero Long entryVersion,
    @NotNull @PositiveOrZero Long paycheckVersion,
    @NotBlank @Size(max = 160) String name,
    @NotNull RecurringBillAmountMode amountMode,
    @Schema(types = {"integer", "null"})
        @PositiveOrZero(message = "Typical amount must be greater than or equal to $0.00.")
        Long typicalAmountMinor,
    EntryPaymentMethod paymentMethod,
    @Min(1) @Max(31) int dueDay,
    @Size(max = 160) String accountName,
    @Size(max = 160) String payee,
    @Size(max = 2000) String notes,
    @NotNull LocalDate occurrenceDate) {}
