package com.yuuka.backend.bucket.api.dto;

import java.time.LocalDate;
import java.util.UUID;

public record SpendingBucketInsightPointResponse(
    UUID paycheckId,
    String paycheckName,
    LocalDate incomeDate,
    long matchingBucketCount,
    long budgetedMinor,
    long spentMinor,
    long netMinor) {}
