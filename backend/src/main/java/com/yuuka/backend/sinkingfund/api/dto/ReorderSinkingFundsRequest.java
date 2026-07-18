package com.yuuka.backend.sinkingfund.api.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record ReorderSinkingFundsRequest(@NotEmpty List<UUID> sinkingFundIds) {
  public ReorderSinkingFundsRequest {
    sinkingFundIds = sinkingFundIds == null ? null : List.copyOf(sinkingFundIds);
  }
}
