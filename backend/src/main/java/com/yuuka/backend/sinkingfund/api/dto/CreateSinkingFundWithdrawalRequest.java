package com.yuuka.backend.sinkingfund.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateSinkingFundWithdrawalRequest(
    @NotNull(message = "Enter a withdrawal amount.")
        @Positive(message = "Withdrawal amount must be greater than $0.00.")
        Long amountMinor,
    LocalDate effectiveDate,
    @NotBlank @Size(max = 500) String reason,
    @Size(max = 2000) String notes,
    @NotNull @PositiveOrZero Long version) {}
