package com.yuuka.backend.auth.api.dto;

import java.time.Instant;

public record AuthResponse(
    String accessToken,
    String tokenType,
    Instant expiresAt,
    String refreshToken,
    Instant refreshExpiresAt) {}
