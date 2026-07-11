package com.yuuka.backend.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "entity_type", nullable = false, length = 60)
  private String entityType;

  @Column(name = "entity_id", nullable = false)
  private UUID entityId;

  @Column(nullable = false, length = 80)
  private String action;

  @Column(name = "effective_at")
  private Instant effectiveAt;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "before_data", columnDefinition = "jsonb")
  private String beforeData;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "after_data", columnDefinition = "jsonb")
  private String afterData;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String metadata;

  protected AuditEvent() {}

  public AuditEvent(
      UUID ownerId,
      String entityType,
      UUID entityId,
      String action,
      Instant effectiveAt,
      Instant recordedAt,
      String beforeData,
      String afterData,
      String metadata) {
    this.ownerId = ownerId;
    this.entityType = entityType;
    this.entityId = entityId;
    this.action = action;
    this.effectiveAt = effectiveAt;
    this.recordedAt = recordedAt;
    this.beforeData = beforeData;
    this.afterData = afterData;
    this.metadata = metadata;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getEntityType() {
    return entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public String getAction() {
    return action;
  }

  public Instant getEffectiveAt() {
    return effectiveAt;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public String getBeforeData() {
    return beforeData;
  }

  public String getAfterData() {
    return afterData;
  }

  public String getMetadata() {
    return metadata;
  }
}
