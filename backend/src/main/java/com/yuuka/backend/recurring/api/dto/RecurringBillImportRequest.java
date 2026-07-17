package com.yuuka.backend.recurring.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record RecurringBillImportRequest(
    @PositiveOrZero long paycheckVersion,
    @NotEmpty List<@Valid RecurringBillImportItemRequest> items) {
  public RecurringBillImportRequest {
    items = items == null ? null : List.copyOf(items);
  }
}
