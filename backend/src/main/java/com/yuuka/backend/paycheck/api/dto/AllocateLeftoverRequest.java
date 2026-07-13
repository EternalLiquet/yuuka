package com.yuuka.backend.paycheck.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AllocateLeftoverRequest(@NotNull @PositiveOrZero Long paycheckVersion) {}
