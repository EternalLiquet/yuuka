package com.yuuka.backend.paycheck.domain;

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
@Table(name = "paycheck_entries")
public class PaycheckEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "paycheck_id", nullable = false)
  private UUID paycheckId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 32)
  private EntryType entryType;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private EntryStatus status = EntryStatus.NOT_PAID;

  @Column(nullable = false)
  private int position;

  @Column(name = "due_date")
  private LocalDate dueDate;

  @Column(name = "account_name", length = 160)
  private String accountName;

  @Column(length = 160)
  private String payee;

  @Column(length = 2000)
  private String notes;

  @Column(name = "target_minor")
  private Long targetMinor;

  @Column(name = "target_date")
  private LocalDate targetDate;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected PaycheckEntry() {}

  public PaycheckEntry(
      UUID ownerId,
      UUID paycheckId,
      EntryType entryType,
      String name,
      long amountMinor,
      int position,
      LocalDate dueDate,
      String accountName,
      String payee,
      String notes,
      Long targetMinor,
      LocalDate targetDate) {
    this.ownerId = ownerId;
    this.paycheckId = paycheckId;
    this.entryType = entryType;
    this.name = name;
    this.amountMinor = amountMinor;
    this.position = position;
    this.dueDate = dueDate;
    this.accountName = accountName;
    this.payee = payee;
    this.notes = notes;
    this.targetMinor = targetMinor;
    this.targetDate = targetDate;
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
      EntryType entryType,
      String name,
      long amountMinor,
      LocalDate dueDate,
      String accountName,
      String payee,
      String notes,
      Long targetMinor,
      LocalDate targetDate) {
    this.entryType = entryType;
    this.name = name;
    this.amountMinor = amountMinor;
    this.dueDate = dueDate;
    this.accountName = accountName;
    this.payee = payee;
    this.notes = notes;
    this.targetMinor = targetMinor;
    this.targetDate = targetDate;
  }

  public EntryStatus transitionTo(EntryStatus nextStatus) {
    EntryStatus previous = status;
    status = nextStatus;
    return previous;
  }

  public void moveTo(int nextPosition) {
    position = nextPosition;
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

  public UUID getPaycheckId() {
    return paycheckId;
  }

  public EntryType getEntryType() {
    return entryType;
  }

  public String getName() {
    return name;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public EntryStatus getStatus() {
    return status;
  }

  public int getPosition() {
    return position;
  }

  public LocalDate getDueDate() {
    return dueDate;
  }

  public String getAccountName() {
    return accountName;
  }

  public String getPayee() {
    return payee;
  }

  public String getNotes() {
    return notes;
  }

  public Long getTargetMinor() {
    return targetMinor;
  }

  public LocalDate getTargetDate() {
    return targetDate;
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

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public long getVersion() {
    return version;
  }
}
