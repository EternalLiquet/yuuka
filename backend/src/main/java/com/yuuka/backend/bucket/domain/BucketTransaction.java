package com.yuuka.backend.bucket.domain;

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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "bucket_transactions")
public class BucketTransaction {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "entry_id", nullable = false)
  private UUID entryId;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(length = 500)
  private String description;

  @Column(length = 1000)
  private String notes;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected BucketTransaction() {}

  public BucketTransaction(
      UUID ownerId,
      UUID entryId,
      long amountMinor,
      String description,
      String notes,
      LocalDate effectiveDate) {
    this.ownerId = ownerId;
    this.entryId = entryId;
    this.amountMinor = amountMinor;
    this.description = description;
    this.notes = notes;
    this.effectiveDate = effectiveDate;
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

  public void update(long amountMinor, String description, String notes, LocalDate effectiveDate) {
    this.amountMinor = amountMinor;
    this.description = description;
    this.notes = notes;
    this.effectiveDate = effectiveDate;
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

  public UUID getEntryId() {
    return entryId;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getDescription() {
    return description;
  }

  public String getNotes() {
    return notes;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
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
