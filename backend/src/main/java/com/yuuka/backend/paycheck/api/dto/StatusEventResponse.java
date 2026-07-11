package com.yuuka.backend.paycheck.api.dto;

import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryStatusEvent;
import java.time.Instant;
import java.util.UUID;

public record StatusEventResponse(
    UUID id,
    UUID entryId,
    EntryStatus fromStatus,
    EntryStatus toStatus,
    Instant effectiveAt,
    Instant recordedAt,
    String note) {
  public static StatusEventResponse from(EntryStatusEvent event) {
    return new StatusEventResponse(
        event.getId(),
        event.getEntryId(),
        event.getFromStatus(),
        event.getToStatus(),
        event.getEffectiveAt(),
        event.getRecordedAt(),
        event.getNote());
  }
}
