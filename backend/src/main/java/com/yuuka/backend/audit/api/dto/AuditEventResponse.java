package com.yuuka.backend.audit.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.audit.domain.AuditEvent;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    String entityType,
    UUID entityId,
    String action,
    Instant effectiveAt,
    Instant recordedAt,
    JsonNode beforeData,
    JsonNode afterData,
    JsonNode metadata) {
  public static AuditEventResponse from(AuditEvent event, ObjectMapper objectMapper) {
    return new AuditEventResponse(
        event.getId(),
        event.getEntityType(),
        event.getEntityId(),
        event.getAction(),
        event.getEffectiveAt(),
        event.getRecordedAt(),
        read(event.getBeforeData(), objectMapper),
        read(event.getAfterData(), objectMapper),
        read(event.getMetadata(), objectMapper));
  }

  private static JsonNode read(String value, ObjectMapper objectMapper) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.readTree(value);
    } catch (Exception exception) {
      throw new IllegalStateException("Stored audit JSON is invalid", exception);
    }
  }
}
