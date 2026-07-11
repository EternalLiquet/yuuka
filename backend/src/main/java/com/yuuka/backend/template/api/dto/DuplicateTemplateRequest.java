package com.yuuka.backend.template.api.dto;

import jakarta.validation.constraints.Size;

public record DuplicateTemplateRequest(@Size(max = 120) String name) {}
