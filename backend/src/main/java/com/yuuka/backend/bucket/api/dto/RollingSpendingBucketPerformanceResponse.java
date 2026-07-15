package com.yuuka.backend.bucket.api.dto;

import java.time.LocalDate;

public record RollingSpendingBucketPerformanceResponse(
    LocalDate asOfDate,
    LocalDate windowStartDate,
    LocalDate windowEndDate,
    long paycheckCount,
    SpendingBucketPerformanceSummaryResponse summary) {}
