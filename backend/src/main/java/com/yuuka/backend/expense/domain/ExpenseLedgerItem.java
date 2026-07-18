package com.yuuka.backend.expense.domain;

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
@Table(name = "expense_ledger_items")
public class ExpenseLedgerItem {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "ledger_id", nullable = false)
  private UUID ledgerId;

  @Column(length = 160)
  private String name;

  @Column(length = 160)
  private String merchant;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(name = "expense_date", nullable = false)
  private LocalDate expenseDate;

  @Column(length = 2000)
  private String notes;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected ExpenseLedgerItem() {}

  public ExpenseLedgerItem(
      UUID ownerId,
      UUID ledgerId,
      String name,
      String merchant,
      long amountMinor,
      LocalDate expenseDate,
      String notes) {
    this.ownerId = ownerId;
    this.ledgerId = ledgerId;
    this.name = name;
    this.merchant = merchant;
    this.amountMinor = amountMinor;
    this.expenseDate = expenseDate;
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

  public void update(
      String name, String merchant, long amountMinor, LocalDate expenseDate, String notes) {
    this.name = name;
    this.merchant = merchant;
    this.amountMinor = amountMinor;
    this.expenseDate = expenseDate;
    this.notes = notes;
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

  public UUID getLedgerId() {
    return ledgerId;
  }

  public String getName() {
    return name;
  }

  public String getMerchant() {
    return merchant;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public LocalDate getExpenseDate() {
    return expenseDate;
  }

  public String getNotes() {
    return notes;
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
