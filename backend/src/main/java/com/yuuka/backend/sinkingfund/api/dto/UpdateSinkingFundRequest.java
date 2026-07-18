package com.yuuka.backend.sinkingfund.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateSinkingFundRequest(
    @NotBlank @Size(max = 160) String name,
    @PositiveOrZero(message = "Target amount must be greater than or equal to $0.00.")
        Long targetMinor,
    LocalDate targetDate,
    @Size(max = 2000) String notes,
    @NotNull @PositiveOrZero Long version) {}
