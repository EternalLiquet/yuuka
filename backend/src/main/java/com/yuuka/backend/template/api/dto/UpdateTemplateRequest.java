package com.yuuka.backend.template.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateTemplateRequest(
    @NotBlank @Size(max = 120) String name,
    @Size(max = 1000) String description,
    @NotNull @PositiveOrZero Long version) {}
