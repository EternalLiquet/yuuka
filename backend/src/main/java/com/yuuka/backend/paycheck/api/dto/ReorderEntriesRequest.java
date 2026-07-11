package com.yuuka.backend.paycheck.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

public record ReorderEntriesRequest(
    @NotEmpty List<@NotNull UUID> entryIds, @NotNull @PositiveOrZero Long paycheckVersion) {
  public ReorderEntriesRequest {
    entryIds = entryIds == null ? null : List.copyOf(entryIds);
  }
}
