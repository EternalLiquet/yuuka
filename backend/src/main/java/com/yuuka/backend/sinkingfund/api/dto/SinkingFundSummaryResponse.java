package com.yuuka.backend.sinkingfund.api.dto;

public record SinkingFundSummaryResponse(
    long totalActiveBalanceMinor, int activeCount, int archivedCount) {}
