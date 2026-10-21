package com.yuuka.backend.paycheck.domain;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Decides when a Processing entry has been Processing long enough to need attention. Both dates are
 * owner-local dates: the processing-since date is the owner-local date of the user-selected
 * effective timestamp of the latest status event, never the system-recorded timestamp.
 */
@Component
public class ProcessingAgePolicy {
  public static final int AGED_AFTER_DAYS = 3;

  /** True when the entry started Processing on or before {@code today} minus three days. */
  public boolean isAged(LocalDate processingSince, LocalDate today) {
    return !processingSince.isAfter(today.minusDays(AGED_AFTER_DAYS));
  }
}
