package com.yuuka.backend.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record VersionResponse(
    @Schema(minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED) String version) {}
