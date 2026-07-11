package com.yuuka.backend.bucket.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateBucketTransactionRequest(
    @NotNull Long amountMinor,
    @Size(max = 500) String description,
    @NotNull LocalDate effectiveDate) {}
