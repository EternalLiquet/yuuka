package com.yuuka.backend.dashboard.api.dto;

public record DashboardPlannedSavingsSummaryResponse(
    long totalActiveReservedBalanceMinor, int activeCount) {}
