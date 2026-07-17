package com.yuuka.backend.bucket.infrastructure;

import java.util.UUID;

public interface BucketTransactionTotalProjection {
  UUID getEntryId();

  Long getSpentMinor();
}
