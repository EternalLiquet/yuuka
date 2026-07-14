package com.yuuka.backend.bucket.api.dto;

public record SpendingBucketPerformanceSummaryResponse(
    long budgetedMinor, long spentMinor, long netMinor) {}
