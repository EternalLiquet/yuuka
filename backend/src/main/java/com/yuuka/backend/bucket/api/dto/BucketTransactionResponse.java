package com.yuuka.backend.bucket.api.dto;

import com.yuuka.backend.bucket.domain.BucketTransaction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BucketTransactionResponse(
    UUID id,
    UUID entryId,
    long amountMinor,
    String description,
    LocalDate effectiveDate,
    Instant createdAt,
    Instant updatedAt,
    long version) {
  public static BucketTransactionResponse from(BucketTransaction transaction) {
    return new BucketTransactionResponse(
        transaction.getId(),
        transaction.getEntryId(),
        transaction.getAmountMinor(),
        transaction.getDescription(),
        transaction.getEffectiveDate(),
        transaction.getCreatedAt(),
        transaction.getUpdatedAt(),
        transaction.getVersion());
  }
}
