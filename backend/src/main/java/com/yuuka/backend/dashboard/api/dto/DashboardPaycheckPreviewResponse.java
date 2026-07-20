package com.yuuka.backend.dashboard.api.dto;

import java.time.LocalDate;
import java.util.UUID;

public record DashboardPaycheckPreviewResponse(
    UUID paycheckId,
    String name,
    LocalDate incomeDate,
    long amountMinor,
    long unallocatedMinor,
    long notPaidCount,
    long processingCount) {}
