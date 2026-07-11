package com.yuuka.backend.paycheck.domain;

public record AllocationLine(long amountMinor, EntryStatus status, boolean deleted) {
  public AllocationLine {
    if (amountMinor < 0) {
      throw new IllegalArgumentException("Entry amount must not be negative");
    }
    if (status == null) {
      throw new IllegalArgumentException("Entry status is required");
    }
  }
}
