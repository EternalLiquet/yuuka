package com.yuuka.backend.paycheck.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuuka.backend.common.api.BusinessRuleException;
import org.junit.jupiter.api.Test;

class StatusTransitionPolicyTests {
  private final StatusTransitionPolicy policy = new StatusTransitionPolicy();

  @Test
  void permitsForwardAndBackwardTransitions() {
    assertThatCode(() -> policy.requireChange(EntryStatus.NOT_PAID, EntryStatus.PROCESSING))
        .doesNotThrowAnyException();
    assertThatCode(() -> policy.requireChange(EntryStatus.POSTED, EntryStatus.PROCESSING))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAStatusThatDoesNotChange() {
    assertThatThrownBy(() -> policy.requireChange(EntryStatus.POSTED, EntryStatus.POSTED))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessage("The entry already has that status.");
  }
}
