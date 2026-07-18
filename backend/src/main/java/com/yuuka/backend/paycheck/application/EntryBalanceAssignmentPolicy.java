package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.common.api.BusinessRuleException;
import java.util.Map;
import java.util.UUID;

public final class EntryBalanceAssignmentPolicy {
  public static final String MULTIPLE_ASSIGNMENTS_CODE = "ENTRY_MULTIPLE_BALANCE_ASSIGNMENTS";

  private EntryBalanceAssignmentPolicy() {}

  public static void requireExclusive(UUID paybackId, UUID sinkingFundId) {
    if (paybackId != null && sinkingFundId != null) {
      throw new BusinessRuleException(
          MULTIPLE_ASSIGNMENTS_CODE,
          "An entry cannot be assigned to both a Payback and a persistent Sinking Fund.",
          Map.of());
    }
  }
}
