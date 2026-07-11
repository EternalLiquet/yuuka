package com.yuuka.backend.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.audit.api.dto.AuditEventResponse;
import com.yuuka.backend.audit.domain.AuditEvent;
import com.yuuka.backend.audit.infrastructure.JpaAuditEventRepository;
import com.yuuka.backend.common.api.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JpaAuditEventRepository auditEvents;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public AuditService(JpaAuditEventRepository auditEvents, ObjectMapper objectMapper, Clock clock) {
    this.auditEvents = auditEvents;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  public AuditEvent append(
      UUID ownerId,
      String entityType,
      UUID entityId,
      String action,
      Instant effectiveAt,
      Object before,
      Object after,
      Object metadata) {
    return auditEvents.save(
        new AuditEvent(
            ownerId,
            entityType,
            entityId,
            action,
            effectiveAt,
            clock.instant(),
            toJson(before),
            toJson(after),
            toJson(metadata)));
  }

  public PageResponse<AuditEventResponse> findForEntity(
      UUID ownerId, String entityType, UUID entityId, int page, int size) {
    PageRequest request =
        PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), 100),
            Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.desc("id")));
    return PageResponse.from(
        auditEvents
            .findAllByOwnerIdAndEntityTypeAndEntityId(ownerId, entityType, entityId, request)
            .map(event -> AuditEventResponse.from(event, objectMapper)));
  }

  private String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize audit data", exception);
    }
  }
}
