package com.yuuka.backend.paycheck.domain;

import com.yuuka.backend.common.api.BusinessRuleException;
import org.springframework.stereotype.Component;

@Component
public class StatusTransitionPolicy {
  public void requireChange(EntryStatus current, EntryStatus requested) {
    if (current == requested) {
      throw new BusinessRuleException("The entry already has that status.");
    }
  }
}
