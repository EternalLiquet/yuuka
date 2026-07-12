package com.yuuka.backend.bucket.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateBucketTransactionRequest(
    @NotNull @Positive Long amountMinor,
    @Size(max = 500) String description,
    @Size(max = 1000) String notes,
    @NotNull LocalDate effectiveDate,
    @NotNull @PositiveOrZero Long version) {}
