package com.yuuka.backend.payback.api.dto;

import com.yuuka.backend.common.api.MoneyArithmetic;
import com.yuuka.backend.payback.domain.Payback;
import com.yuuka.backend.payback.domain.PaybackState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaybackResponse(
    UUID id,
    String name,
    long originalAmountMinor,
    long openingRemainingAmountMinor,
    long repaidMinor,
    long remainingMinor,
    double progressPercent,
    LocalDate borrowedDate,
    String source,
    String notes,
    PaybackState state,
    int position,
    long repaymentCount,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static PaybackResponse from(Payback payback, long repaidMinor, long repaymentCount) {
    long remainingMinor =
        MoneyArithmetic.subtract(payback.getOpeningRemainingAmountMinor(), repaidMinor);
    double progress =
        payback.getOpeningRemainingAmountMinor() == 0
            ? 100
            : Math.min(
                Math.max(
                    ((double) repaidMinor / (double) payback.getOpeningRemainingAmountMinor())
                        * 100,
                    0),
                100);
    return new PaybackResponse(
        payback.getId(),
        payback.getName(),
        payback.getOriginalAmountMinor(),
        payback.getOpeningRemainingAmountMinor(),
        repaidMinor,
        remainingMinor,
        progress,
        payback.getBorrowedDate(),
        payback.getSource(),
        payback.getNotes(),
        payback.getState(),
        payback.getPosition(),
        repaymentCount,
        payback.getCreatedAt(),
        payback.getUpdatedAt(),
        payback.getVersion());
  }
}
