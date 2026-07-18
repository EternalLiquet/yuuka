package com.yuuka.backend.sinkingfund.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sinking_funds")
public class SinkingFund {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(name = "target_minor")
  private Long targetMinor;

  @Column(name = "target_date")
  private LocalDate targetDate;

  @Column(length = 2000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SinkingFundState state = SinkingFundState.ACTIVE;

  @Column(nullable = false)
  private int position;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected SinkingFund() {}

  public SinkingFund(
      UUID ownerId,
      String name,
      Long targetMinor,
      LocalDate targetDate,
      String notes,
      int position) {
    this.ownerId = ownerId;
    this.name = name;
    this.targetMinor = targetMinor;
    this.targetDate = targetDate;
    this.notes = notes;
    this.position = position;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public void update(String name, Long targetMinor, LocalDate targetDate, String notes) {
    this.name = name;
    this.targetMinor = targetMinor;
    this.targetDate = targetDate;
    this.notes = notes;
  }

  public void archive(Instant now) {
    state = SinkingFundState.ARCHIVED;
    archivedAt = now;
    updatedAt = now;
  }

  public void restore(Instant now) {
    state = SinkingFundState.ACTIVE;
    archivedAt = null;
    updatedAt = now;
  }

  public void touch(Instant now) {
    updatedAt = now;
  }

  public void moveTo(int nextPosition) {
    position = nextPosition;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getName() {
    return name;
  }

  public Long getTargetMinor() {
    return targetMinor;
  }

  public LocalDate getTargetDate() {
    return targetDate;
  }

  public String getNotes() {
    return notes;
  }

  public SinkingFundState getState() {
    return state;
  }

  public int getPosition() {
    return position;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public long getVersion() {
    return version;
  }
}
