package com.yuuka.backend.sinkingfund.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record AssignSinkingFundRequest(UUID sinkingFundId, @NotNull @PositiveOrZero Long version) {}
