package com.yuuka.backend.template.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateTemplateRequest(
    @NotBlank @Size(max = 120) String name,
    @Size(max = 1000) String description,
    List<@Valid TemplateEntryRequest> entries) {
  public CreateTemplateRequest {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }
}
