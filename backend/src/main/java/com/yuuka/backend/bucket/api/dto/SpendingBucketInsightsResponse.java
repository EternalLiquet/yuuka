package com.yuuka.backend.bucket.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record SpendingBucketInsightsResponse(
    LocalDate asOfDate,
    int recentPaycheckLimit,
    int qualifyingPaycheckCount,
    @Schema(allowableValues = {"ALL", "BUCKET_NAME"}) String scope,
    String selectedBucketName,
    List<String> availableBucketNames,
    List<SpendingBucketInsightPointResponse> points) {}
