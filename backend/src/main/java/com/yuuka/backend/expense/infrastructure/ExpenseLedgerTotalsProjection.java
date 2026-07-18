package com.yuuka.backend.expense.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface ExpenseLedgerTotalsProjection {
  UUID getLedgerId();

  BigDecimal getTotalMinor();

  long getItemCount();

  LocalDate getLatestExpenseDate();
}
