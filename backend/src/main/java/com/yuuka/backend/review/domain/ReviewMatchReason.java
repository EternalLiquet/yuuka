package com.yuuka.backend.review.domain;

/** Why an existing entry was listed as a possible, unconfirmed match for an occurrence. */
public enum ReviewMatchReason {
  NAME_EQUALS_IGNORING_CASE,
  AMOUNT_EQUALS_OCCURRENCE_OR_TYPICAL,
  SAME_RECURRING_DEFINITION
}
