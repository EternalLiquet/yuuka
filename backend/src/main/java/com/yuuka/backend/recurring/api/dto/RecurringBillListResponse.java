package com.yuuka.backend.recurring.api.dto;

import java.util.List;

public record RecurringBillListResponse(List<RecurringBillResponse> items) {
  public RecurringBillListResponse {
    items = List.copyOf(items);
  }
}
