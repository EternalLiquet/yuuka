package com.yuuka.backend.sinkingfund.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateSinkingFundRequest(
    @NotBlank @Size(max = 160) String name,
    @PositiveOrZero(message = "Target amount must be greater than or equal to $0.00.")
        Long targetMinor,
    LocalDate targetDate,
    @Size(max = 2000) String notes,
    @Positive(message = "Opening balance must be greater than $0.00.") Long openingBalanceMinor) {}
