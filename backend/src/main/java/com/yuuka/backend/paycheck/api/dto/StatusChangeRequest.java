package com.yuuka.backend.paycheck.api.dto;

import com.yuuka.backend.paycheck.domain.EntryStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record StatusChangeRequest(
    @NotNull EntryStatus toStatus,
    @NotNull Instant effectiveAt,
    @Size(max = 1000) String note,
    @PositiveOrZero Long version) {}
