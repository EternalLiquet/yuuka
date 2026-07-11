package com.yuuka.backend.paycheck.domain;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class PaycheckVisibilityPolicy {
  public boolean belongsInActive(
      PaycheckState state, boolean requiresAttention, Instant reopenedAt) {
    return state == PaycheckState.ACTIVE && (requiresAttention || reopenedAt != null);
  }

  public boolean belongsInHistory(
      PaycheckState state, boolean requiresAttention, Instant reopenedAt) {
    return state != PaycheckState.ACTIVE || (!requiresAttention && reopenedAt == null);
  }
}
