package com.yuuka.backend.paycheck.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreatePaycheckRequest(
    @NotBlank @Size(max = 120) String name,
    @NotNull(message = "Enter an amount.")
        @PositiveOrZero(message = "Amount must be greater than or equal to $0.00.")
        Long amountMinor,
    @NotNull LocalDate incomeDate,
    @Size(max = 160) String source,
    @Size(max = 2000) String notes) {}
