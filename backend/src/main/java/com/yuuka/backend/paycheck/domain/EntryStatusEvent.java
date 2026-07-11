package com.yuuka.backend.paycheck.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entry_status_events")
public class EntryStatusEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "entry_id", nullable = false)
  private UUID entryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 20)
  private EntryStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 20)
  private EntryStatus toStatus;

  @Column(name = "effective_at", nullable = false)
  private Instant effectiveAt;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  @Column(length = 1000)
  private String note;

  protected EntryStatusEvent() {}

  public EntryStatusEvent(
      UUID ownerId,
      UUID entryId,
      EntryStatus fromStatus,
      EntryStatus toStatus,
      Instant effectiveAt,
      Instant recordedAt,
      String note) {
    this.ownerId = ownerId;
    this.entryId = entryId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.effectiveAt = effectiveAt;
    this.recordedAt = recordedAt;
    this.note = note;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getEntryId() {
    return entryId;
  }

  public EntryStatus getFromStatus() {
    return fromStatus;
  }

  public EntryStatus getToStatus() {
    return toStatus;
  }

  public Instant getEffectiveAt() {
    return effectiveAt;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public String getNote() {
    return note;
  }
}
