package com.yuuka.backend.sinkingfund.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SinkingFundVersionRequest(
    @NotNull @PositiveOrZero Long version, boolean confirmPositiveBalance) {}
