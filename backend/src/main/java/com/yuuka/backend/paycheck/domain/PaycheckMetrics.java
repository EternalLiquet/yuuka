package com.yuuka.backend.paycheck.domain;

import java.math.BigDecimal;

public record PaycheckMetrics(
    long allocatedMinor,
    long unallocatedMinor,
    long postedMinor,
    long processingMinor,
    long notPaidMinor,
    long postedCount,
    long processingCount,
    long notPaidCount,
    BigDecimal allocationPercent,
    BigDecimal completionPercent) {
  public boolean fullyAllocated() {
    return unallocatedMinor == 0;
  }

  public boolean fullyPosted() {
    return liveEntryCount() > 0
        && notPaidCount == 0
        && processingCount == 0
        && allocatedMinor == postedMinor;
  }

  public boolean requiresAttention() {
    return !fullyAllocated() || !fullyPosted();
  }

  private long liveEntryCount() {
    return postedCount + processingCount + notPaidCount;
  }
}
