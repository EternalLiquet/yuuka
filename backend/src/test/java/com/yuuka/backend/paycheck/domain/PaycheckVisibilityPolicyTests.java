package com.yuuka.backend.paycheck.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaycheckVisibilityPolicyTests {
  private final PaycheckVisibilityPolicy policy = new PaycheckVisibilityPolicy();

  @Test
  void activeContainsIncompleteAndExplicitlyReopenedPaychecks() {
    assertThat(policy.belongsInActive(PaycheckState.ACTIVE, true, null)).isTrue();
    assertThat(
            policy.belongsInActive(
                PaycheckState.ACTIVE, false, Instant.parse("2026-07-10T12:00:00Z")))
        .isTrue();
    assertThat(policy.belongsInActive(PaycheckState.ACTIVE, false, null)).isFalse();
    assertThat(policy.belongsInActive(PaycheckState.CLOSED, true, null)).isFalse();
  }

  @Test
  void historyContainsClosedArchivedAndCompletedNonReopenedPaychecks() {
    assertThat(policy.belongsInHistory(PaycheckState.CLOSED, true, null)).isTrue();
    assertThat(policy.belongsInHistory(PaycheckState.ARCHIVED, false, null)).isTrue();
    assertThat(policy.belongsInHistory(PaycheckState.ACTIVE, false, null)).isTrue();
    assertThat(
            policy.belongsInHistory(
                PaycheckState.ACTIVE, false, Instant.parse("2026-07-10T12:00:00Z")))
        .isFalse();
    assertThat(policy.belongsInHistory(PaycheckState.ACTIVE, true, null)).isFalse();
  }
}
