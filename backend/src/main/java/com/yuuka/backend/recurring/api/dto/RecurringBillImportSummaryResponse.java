package com.yuuka.backend.recurring.api.dto;

import com.yuuka.backend.paycheck.domain.EntryStatus;
import java.util.UUID;

public record RecurringBillImportSummaryResponse(
    UUID entryId, UUID paycheckId, String paycheckName, EntryStatus status) {}
