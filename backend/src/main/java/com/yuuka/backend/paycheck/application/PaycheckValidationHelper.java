package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class PaycheckValidationHelper {
  void assertNotOverAllocated(PaycheckMetrics metrics) {
    if (metrics.unallocatedMinor() < 0) {
      throw new BusinessRuleException(
          "PAYCHECK_OVER_ALLOCATED",
          "This would over-allocate the paycheck.",
          Map.of("amountMinor", Math.abs(metrics.unallocatedMinor()), "currencyCode", "USD"));
    }
  }

  void assertVersion(long actual, long supplied) {
    if (actual != supplied) {
      throw new ConflictException(
          "This record changed since it was loaded. Refresh and try again.");
    }
  }

  void requireActive(Paycheck paycheck) {
    if (paycheck.getState() != PaycheckState.ACTIVE) {
      throw new BusinessRuleException("Reopen the paycheck before changing it.");
    }
  }

  String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
