package com.yuuka.backend.sinkingfund.api.dto;

import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.sinkingfund.domain.SinkingFundTransaction;
import com.yuuka.backend.sinkingfund.domain.SinkingFundTransactionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SinkingFundTransactionResponse(
    UUID id,
    UUID sinkingFundId,
    UUID entryId,
    SinkingFundTransactionType transactionType,
    long amountMinor,
    LocalDate effectiveDate,
    String reason,
    String notes,
    String paycheckName,
    LocalDate paycheckIncomeDate,
    String entryName,
    EntryStatus entryStatus,
    Instant reversedAt,
    String reversalReason,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static SinkingFundTransactionResponse from(
      SinkingFundTransaction transaction, PaycheckEntry entry, Paycheck paycheck) {
    return new SinkingFundTransactionResponse(
        transaction.getId(),
        transaction.getSinkingFundId(),
        transaction.getEntryId(),
        transaction.getTransactionType(),
        transaction.getAmountMinor(),
        transaction.getEffectiveDate(),
        transaction.getReason(),
        transaction.getNotes(),
        paycheck == null ? null : paycheck.getName(),
        paycheck == null ? null : paycheck.getIncomeDate(),
        entry == null ? null : entry.getName(),
        entry == null ? null : entry.getStatus(),
        transaction.getReversedAt(),
        transaction.getReversalReason(),
        transaction.getCreatedAt(),
        transaction.getUpdatedAt(),
        transaction.getVersion());
  }
}
