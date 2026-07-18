package com.yuuka.backend.sinkingfund.api.dto;

import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.sinkingfund.domain.SinkingFund;
import com.yuuka.backend.sinkingfund.domain.SinkingFundState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SinkingFundResponse(
    UUID id,
    String name,
    Long targetMinor,
    LocalDate targetDate,
    String notes,
    SinkingFundState state,
    int position,
    long currentBalanceMinor,
    Long remainingTargetMinor,
    Double progressPercent,
    long transactionCount,
    Instant createdAt,
    Instant updatedAt,
    Instant archivedAt,
    long version) {
  public static SinkingFundResponse from(
      SinkingFund fund, long currentBalanceMinor, long transactionCount) {
    Long remainingTargetMinor =
        fund.getTargetMinor() == null
            ? null
            : MoneyArithmetic.subtract(fund.getTargetMinor(), currentBalanceMinor);
    Double progressPercent =
        fund.getTargetMinor() == null
            ? null
            : fund.getTargetMinor() == 0
                ? 100.0
                : ((double) currentBalanceMinor / (double) fund.getTargetMinor()) * 100;
    return new SinkingFundResponse(
        fund.getId(),
        fund.getName(),
        fund.getTargetMinor(),
        fund.getTargetDate(),
        fund.getNotes(),
        fund.getState(),
        fund.getPosition(),
        currentBalanceMinor,
        remainingTargetMinor,
        progressPercent,
        transactionCount,
        fund.getCreatedAt(),
        fund.getUpdatedAt(),
        fund.getArchivedAt(),
        fund.getVersion());
  }
}
