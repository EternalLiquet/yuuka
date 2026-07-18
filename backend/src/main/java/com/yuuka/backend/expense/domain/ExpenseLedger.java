package com.yuuka.backend.expense.domain;

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
import java.util.UUID;

@Entity
@Table(name = "expense_ledgers")
public class ExpenseLedger {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(length = 2000)
  private String notes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ExpenseLedgerState state = ExpenseLedgerState.OPEN;

  @Column(name = "finalized_at")
  private Instant finalizedAt;

  @Column(name = "reopened_at")
  private Instant reopenedAt;

  @Column(name = "settled_at")
  private Instant settledAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected ExpenseLedger() {}

  public ExpenseLedger(UUID ownerId, String name, String notes) {
    this.ownerId = ownerId;
    this.name = name;
    this.notes = notes;
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

  public void update(String name, String notes) {
    this.name = name;
    this.notes = notes;
  }

  public void finalizeLedger(Instant now) {
    state = ExpenseLedgerState.FINALIZED;
    finalizedAt = now;
  }

  public void reopen(Instant now) {
    state = ExpenseLedgerState.OPEN;
    reopenedAt = now;
  }

  public void settle(Instant now) {
    state = ExpenseLedgerState.SETTLED;
    settledAt = now;
  }

  public void delete(Instant now) {
    deletedAt = now;
  }

  public void touch(Instant now) {
    updatedAt = now;
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

  public String getNotes() {
    return notes;
  }

  public ExpenseLedgerState getState() {
    return state;
  }

  public Instant getFinalizedAt() {
    return finalizedAt;
  }

  public Instant getReopenedAt() {
    return reopenedAt;
  }

  public Instant getSettledAt() {
    return settledAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
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
