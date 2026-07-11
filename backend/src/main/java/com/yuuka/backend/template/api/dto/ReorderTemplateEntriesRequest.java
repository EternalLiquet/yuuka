package com.yuuka.backend.template.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;

public record ReorderTemplateEntriesRequest(
    @NotEmpty List<@NotNull UUID> entryIds, @NotNull @PositiveOrZero Long templateVersion) {
  public ReorderTemplateEntriesRequest {
    entryIds = entryIds == null ? null : List.copyOf(entryIds);
  }
}
