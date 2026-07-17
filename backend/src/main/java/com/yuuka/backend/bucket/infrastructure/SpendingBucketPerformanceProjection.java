package com.yuuka.backend.bucket.infrastructure;

import java.math.BigDecimal;

public interface SpendingBucketPerformanceProjection {
  Long getBucketCount();

  Long getPaycheckCount();

  BigDecimal getBudgetedMinor();

  BigDecimal getSpentMinor();
}
