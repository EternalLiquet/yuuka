package com.yuuka.backend.paycheck.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreatePaycheckRequest(
    @NotBlank @Size(max = 120) String name,
    @NotNull @PositiveOrZero Long amountMinor,
    @NotNull LocalDate incomeDate,
    @Size(max = 160) String source,
    @Size(max = 2000) String notes) {}
