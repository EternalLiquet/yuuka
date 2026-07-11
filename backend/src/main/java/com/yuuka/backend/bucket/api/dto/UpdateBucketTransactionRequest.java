package com.yuuka.backend.bucket.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateBucketTransactionRequest(
    @NotNull Long amountMinor,
    @Size(max = 500) String description,
    @NotNull LocalDate effectiveDate,
    @NotNull @PositiveOrZero Long version) {}
