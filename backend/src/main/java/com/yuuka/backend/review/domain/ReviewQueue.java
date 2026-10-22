package com.yuuka.backend.review.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** One page of findings plus totals for the single snapshot the whole response was computed in. */
public record ReviewQueue(
    Instant startedAt,
    LocalDate asOfDate,
    String timeZone,
    String currency,
    LocalDate from,
    LocalDate through,
    int page,
    int size,
    long totalItems,
    int totalPages,
    boolean hasNext,
    Map<ReviewCheck, Long> summary,
    List<String> limitations,
    List<ReviewFinding> findings) {
  public ReviewQueue {
    summary = Map.copyOf(summary);
    limitations = List.copyOf(limitations);
    findings = List.copyOf(findings);
  }
}
