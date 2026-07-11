package com.yuuka.backend.bucket.domain;

public record BucketMetrics(long budgetMinor, long spentMinor, long remainingMinor) {
  public boolean overBudget() {
    return remainingMinor < 0;
  }
}
