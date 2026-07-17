package com.yuuka.backend.paycheck.infrastructure;

import java.util.UUID;

public interface PaycheckMetricsProjection {
  UUID getPaycheckId();

  Long getAllocatedMinor();

  Long getPostedMinor();

  Long getProcessingMinor();

  Long getNotPaidMinor();

  Long getPostedCount();

  Long getProcessingCount();

  Long getNotPaidCount();
}
