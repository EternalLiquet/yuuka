package com.yuuka.backend.dashboard.api.dto;

import java.time.LocalDate;
import java.util.List;

public record DashboardSummaryResponse(
    LocalDate asOfDate,
    List<DashboardAttentionItemResponse> needsAttention,
    DashboardActiveSummaryResponse active,
    DashboardPaybackSummaryResponse paybacks,
    DashboardPlannedSavingsSummaryResponse plannedSavings,
    DashboardExpenseListSummaryResponse expenseLists) {}
