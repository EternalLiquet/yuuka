package com.yuuka.backend.payback.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payback_repayments")
public class PaybackRepayment {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "payback_id", nullable = false)
  private UUID paybackId;

  @Column(name = "entry_id", nullable = false)
  private UUID entryId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(name = "applied_at", nullable = false)
  private Instant appliedAt;

  @Column(name = "reversed_at")
  private Instant reversedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected PaybackRepayment() {}

  public PaybackRepayment(
      UUID ownerId, UUID paybackId, UUID entryId, long amountMinor, Instant appliedAt) {
    this.ownerId = ownerId;
    this.paybackId = paybackId;
    this.entryId = entryId;
    this.amountMinor = amountMinor;
    this.appliedAt = appliedAt;
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

  public void reverse(Instant now) {
    reversedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getPaybackId() {
    return paybackId;
  }

  public UUID getEntryId() {
    return entryId;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public Instant getAppliedAt() {
    return appliedAt;
  }

  public Instant getReversedAt() {
    return reversedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
