package com.yuuka.backend.payback.api.dto;

import com.yuuka.backend.payback.domain.PaybackRepayment;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaybackRepaymentResponse(
    UUID id,
    UUID paybackId,
    UUID entryId,
    long amountMinor,
    String paycheckName,
    LocalDate paycheckIncomeDate,
    String entryName,
    EntryStatus entryStatus,
    Instant appliedAt,
    Instant reversedAt,
    long version) {
  public static PaybackRepaymentResponse from(
      PaybackRepayment repayment, PaycheckEntry entry, Paycheck paycheck) {
    return new PaybackRepaymentResponse(
        repayment.getId(),
        repayment.getPaybackId(),
        repayment.getEntryId(),
        repayment.getAmountMinor(),
        paycheck.getName(),
        paycheck.getIncomeDate(),
        entry.getName(),
        entry.getStatus(),
        repayment.getAppliedAt(),
        repayment.getReversedAt(),
        repayment.getVersion());
  }
}
