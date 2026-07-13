package com.yuuka.backend.search.infrastructure;

import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import java.time.LocalDate;
import java.util.UUID;

public interface EntrySearchProjection {
  UUID getEntryId();

  UUID getPaycheckId();

  String getEntryName();

  long getAmountMinor();

  EntryType getEntryType();

  EntryStatus getStatus();

  String getPaycheckName();

  LocalDate getPaycheckIncomeDate();

  PaycheckState getPaycheckState();
}
