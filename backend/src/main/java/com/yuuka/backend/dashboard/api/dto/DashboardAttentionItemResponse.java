package com.yuuka.backend.dashboard.api.dto;

import java.time.LocalDate;
import java.util.UUID;

public record DashboardAttentionItemResponse(
    DashboardAttentionKind kind,
    UUID paycheckId,
    UUID entryId,
    UUID expenseLedgerId,
    String name,
    long amountMinor,
    LocalDate dueDate,
    LocalDate attentionSinceDate) {}
