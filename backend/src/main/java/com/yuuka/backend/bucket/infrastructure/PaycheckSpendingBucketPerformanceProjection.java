package com.yuuka.backend.bucket.infrastructure;

import java.util.UUID;

public interface PaycheckSpendingBucketPerformanceProjection
    extends SpendingBucketPerformanceProjection {
  UUID getPaycheckId();
}
