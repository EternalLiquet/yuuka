package com.yuuka.backend.recurring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "recurring_bill_occurrence_amounts",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_recurring_bill_occurrence_amount",
            columnNames = {"definition_id", "occurrence_date"}))
public class RecurringBillOccurrenceAmount {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "definition_id", nullable = false)
  private UUID definitionId;

  @Column(name = "occurrence_date", nullable = false)
  private LocalDate occurrenceDate;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RecurringBillOccurrenceAmount() {}

  public RecurringBillOccurrenceAmount(
      UUID ownerId, UUID definitionId, LocalDate occurrenceDate, long amountMinor) {
    this.ownerId = ownerId;
    this.definitionId = definitionId;
    this.occurrenceDate = occurrenceDate;
    this.amountMinor = amountMinor;
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

  public void updateAmount(long amountMinor) {
    this.amountMinor = amountMinor;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getDefinitionId() {
    return definitionId;
  }

  public LocalDate getOccurrenceDate() {
    return occurrenceDate;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
