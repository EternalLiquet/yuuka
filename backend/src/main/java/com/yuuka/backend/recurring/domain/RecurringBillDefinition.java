package com.yuuka.backend.recurring.domain;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
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
@Table(name = "recurring_bill_definitions")
public class RecurringBillDefinition {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(name = "typical_amount_minor", nullable = false)
  private long typicalAmountMinor;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_method", nullable = false, length = 20)
  private EntryPaymentMethod paymentMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "recurrence_type", nullable = false, length = 20)
  private RecurringBillRecurrenceType recurrenceType = RecurringBillRecurrenceType.MONTHLY;

  @Column(name = "due_day", nullable = false)
  private int dueDay;

  @Column(name = "account_name", length = 160)
  private String accountName;

  @Column(length = 160)
  private String payee;

  @Column(length = 2000)
  private String notes;

  @Column(nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected RecurringBillDefinition() {}

  public RecurringBillDefinition(
      UUID ownerId,
      String name,
      long typicalAmountMinor,
      EntryPaymentMethod paymentMethod,
      int dueDay,
      String accountName,
      String payee,
      String notes) {
    this.ownerId = ownerId;
    update(name, typicalAmountMinor, paymentMethod, dueDay, accountName, payee, notes);
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
      long typicalAmountMinor,
      EntryPaymentMethod paymentMethod,
      int dueDay,
      String accountName,
      String payee,
      String notes) {
    this.name = name;
    this.typicalAmountMinor = typicalAmountMinor;
    this.paymentMethod = paymentMethod;
    this.dueDay = dueDay;
    this.accountName = accountName;
    this.payee = payee;
    this.notes = notes;
  }

  public void updateTypicalAmount(long typicalAmountMinor) {
    this.typicalAmountMinor = typicalAmountMinor;
  }

  public void activate() {
    active = true;
  }

  public void deactivate() {
    active = false;
  }

  public void delete(Instant now) {
    deletedAt = now;
    active = false;
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

  public long getTypicalAmountMinor() {
    return typicalAmountMinor;
  }

  public EntryPaymentMethod getPaymentMethod() {
    return paymentMethod;
  }

  public RecurringBillRecurrenceType getRecurrenceType() {
    return recurrenceType;
  }

  public int getDueDay() {
    return dueDay;
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

  public boolean isActive() {
    return active;
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
