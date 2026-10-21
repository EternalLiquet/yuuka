package com.yuuka.backend.review.domain;

/** Stable codes of the bookkeeping checks, each with its documented default severity. */
public enum ReviewCheck {
  RECURRING_OCCURRENCE_UNLINKED(ReviewSeverity.MEDIUM),
  RECURRING_OCCURRENCE_MULTIPLE_LINKS(ReviewSeverity.LOW),
  VARIABLE_AMOUNT_NEEDS_REVIEW(ReviewSeverity.MEDIUM),
  MANUAL_BILL_NOT_PAID_DUE(ReviewSeverity.HIGH),
  PROCESSING_AGED(ReviewSeverity.MEDIUM),
  PAYCHECK_UNALLOCATED(ReviewSeverity.LOW),
  BUCKET_OVER_BUDGET(ReviewSeverity.MEDIUM);

  private final ReviewSeverity defaultSeverity;

  ReviewCheck(ReviewSeverity defaultSeverity) {
    this.defaultSeverity = defaultSeverity;
  }

  public ReviewSeverity defaultSeverity() {
    return defaultSeverity;
  }
}
