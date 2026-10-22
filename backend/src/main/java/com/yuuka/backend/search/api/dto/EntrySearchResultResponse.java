package com.yuuka.backend.search.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.search.domain.PaycheckContext;
import com.yuuka.backend.search.domain.SearchResultKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

public record EntrySearchResultResponse(
    SearchResultKind kind,
    UUID entryId,
    UUID paycheckId,
    String entryName,
    long amountMinor,
    EntryType entryType,
    EntryPaymentMethod paymentMethod,
    EntryStatus status,
    String paycheckName,
    LocalDate paycheckIncomeDate,
    PaycheckContext paycheckContext,
    // Retained for the MCP adapter without changing the existing REST/OpenAPI contract.
    @JsonIgnore @Schema(hidden = true) boolean amountEstimated) {}
