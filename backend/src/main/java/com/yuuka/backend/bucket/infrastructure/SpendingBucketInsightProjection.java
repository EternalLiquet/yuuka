package com.yuuka.backend.bucket.infrastructure;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface SpendingBucketInsightProjection {
  UUID getPaycheckId();

  String getPaycheckName();

  LocalDate getIncomeDate();

  String getNormalizedName();

  String getDisplayName();

  Long getMatchingBucketCount();

  BigDecimal getBudgetedMinor();

  BigDecimal getSpentMinor();
}
