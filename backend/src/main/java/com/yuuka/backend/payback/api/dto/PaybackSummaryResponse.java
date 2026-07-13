package com.yuuka.backend.payback.api.dto;

public record PaybackSummaryResponse(
    long totalRemainingMinor, long totalOriginalMinor, long totalRepaidMinor, int activeCount) {}
