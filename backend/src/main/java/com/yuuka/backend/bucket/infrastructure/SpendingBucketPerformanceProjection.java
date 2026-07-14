package com.yuuka.backend.bucket.infrastructure;

public interface SpendingBucketPerformanceProjection {
  Long getBucketCount();

  Long getPaycheckCount();

  Long getBudgetedMinor();

  Long getSpentMinor();
}
