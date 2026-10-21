package com.yuuka.backend.review.domain;

/** Which stated window a finding came from. */
public enum ReviewScope {
  /** Derived from recurring occurrences dated inside the requested inclusive window. */
  OCCURRENCE_WINDOW,
  /** Derived from every Active paycheck regardless of its date. */
  ALL_ACTIVE_PAYCHECKS
}
