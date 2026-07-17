package com.yuuka.backend.auth.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateOwnerSettingsRequest(@Min(1) @Max(31) int recurringBillSuggestionDays) {}
