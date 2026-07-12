package com.yuuka.backend.payback.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreatePaybackRequest(
    @NotBlank @Size(max = 160) String name,
    @NotNull(message = "Enter the original amount.")
        @Positive(message = "Original amount must be greater than $0.00.")
        Long originalAmountMinor,
    @NotNull(message = "Enter the amount currently left.")
        @PositiveOrZero(message = "Amount currently left must be greater than or equal to $0.00.")
        Long openingRemainingAmountMinor,
    @NotNull(message = "Enter a borrowed or start date.") LocalDate borrowedDate,
    @Size(max = 160) String source,
    @Size(max = 2000) String notes) {}
