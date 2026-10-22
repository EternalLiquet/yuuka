package com.yuuka.backend.review.domain;

/** How trustworthy an amount is: fixed by definition, entered variable, estimated, or unknown. */
public enum ReviewAmountBasis {
  FIXED,
  VARIABLE,
  ESTIMATED,
  UNKNOWN
}
