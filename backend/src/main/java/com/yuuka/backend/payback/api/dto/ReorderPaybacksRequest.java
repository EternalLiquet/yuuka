package com.yuuka.backend.payback.api.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record ReorderPaybacksRequest(@NotEmpty List<UUID> paybackIds) {
  public ReorderPaybacksRequest {
    paybackIds = List.copyOf(paybackIds);
  }
}
