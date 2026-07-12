package com.yuuka.backend.payback.domain;

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
@Table(name = "paybacks")
public class Payback {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(name = "original_amount_minor", nullable = false)
  private long originalAmountMinor;

  @Column(name = "opening_remaining_amount_minor", nullable = false)
  private long openingRemainingAmountMinor;

  @Column(name = "borrowed_date", nullable = false)
  private LocalDate borrowedDate;

  @Column(length = 160)
  private String source;

  @Column(length = 2000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PaybackState state;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected Payback() {}

  public Payback(
      UUID ownerId,
      String name,
      long originalAmountMinor,
      long openingRemainingAmountMinor,
      LocalDate borrowedDate,
      String source,
      String notes) {
    this.ownerId = ownerId;
    this.name = name;
    this.originalAmountMinor = originalAmountMinor;
    this.openingRemainingAmountMinor = openingRemainingAmountMinor;
    this.borrowedDate = borrowedDate;
    this.source = source;
    this.notes = notes;
    syncState(openingRemainingAmountMinor);
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

  public void update(
      String name,
      long originalAmountMinor,
      long openingRemainingAmountMinor,
      LocalDate borrowedDate,
      String source,
      String notes,
      long repaidMinor) {
    this.name = name;
    this.originalAmountMinor = originalAmountMinor;
    this.openingRemainingAmountMinor = openingRemainingAmountMinor;
    this.borrowedDate = borrowedDate;
    this.source = source;
    this.notes = notes;
    syncState(openingRemainingAmountMinor - repaidMinor);
  }

  public void syncState(long remainingMinor) {
    state = remainingMinor == 0 ? PaybackState.PAID_OFF : PaybackState.ACTIVE;
  }

  public void delete(Instant now) {
    deletedAt = now;
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

  public long getOriginalAmountMinor() {
    return originalAmountMinor;
  }

  public long getOpeningRemainingAmountMinor() {
    return openingRemainingAmountMinor;
  }

  public LocalDate getBorrowedDate() {
    return borrowedDate;
  }

  public String getSource() {
    return source;
  }

  public String getNotes() {
    return notes;
  }

  public PaybackState getState() {
    return state;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }

  public long getVersion() {
    return version;
  }
}
