package com.yuuka.backend.recurring.api.dto;

import java.time.LocalDate;
import java.util.List;

public record RecurringBillTimelineResponse(
    LocalDate from, LocalDate through, List<RecurringBillOccurrenceResponse> items) {
  public RecurringBillTimelineResponse {
    items = List.copyOf(items);
  }
}
