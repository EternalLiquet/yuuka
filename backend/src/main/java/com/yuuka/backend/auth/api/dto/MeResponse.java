package com.yuuka.backend.auth.api.dto;

import com.yuuka.backend.auth.domain.UserAccount;
import java.time.Instant;
import java.util.UUID;

public record MeResponse(
    UUID id,
    String email,
    String displayName,
    String currencyCode,
    String timezone,
    Instant createdAt,
    Instant updatedAt) {
  public static MeResponse from(UserAccount account) {
    return new MeResponse(
        account.getId(),
        account.getEmail(),
        account.getDisplayName(),
        account.getCurrencyCode(),
        account.getTimezone(),
        account.getCreatedAt(),
        account.getUpdatedAt());
  }
}
