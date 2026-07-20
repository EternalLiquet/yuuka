package com.yuuka.backend.dashboard.api.dto;

import java.util.List;

public record DashboardActiveSummaryResponse(
    long paycheckCount,
    long totalUnallocatedMinor,
    long notPaidEntryCount,
    long processingEntryCount,
    List<DashboardPaycheckPreviewResponse> previews) {}
