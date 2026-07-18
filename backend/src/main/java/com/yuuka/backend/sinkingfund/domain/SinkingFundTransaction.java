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
@Table(name = "sinking_fund_transactions")
public class SinkingFundTransaction {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "sinking_fund_id", nullable = false)
  private UUID sinkingFundId;

  @Column(name = "entry_id")
  private UUID entryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "transaction_type", nullable = false, length = 32)
  private SinkingFundTransactionType transactionType;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(name = "effective_date", nullable = false)
  private LocalDate effectiveDate;

  @Column(length = 500)
  private String reason;

  @Column(length = 2000)
  private String notes;

  @Column(name = "reversed_at")
  private Instant reversedAt;

  @Column(name = "reversal_reason", length = 1000)
  private String reversalReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected SinkingFundTransaction() {}

  public SinkingFundTransaction(
      UUID ownerId,
      UUID sinkingFundId,
      UUID entryId,
      SinkingFundTransactionType transactionType,
      long amountMinor,
      LocalDate effectiveDate,
      String reason,
      String notes) {
    this.ownerId = ownerId;
    this.sinkingFundId = sinkingFundId;
    this.entryId = entryId;
    this.transactionType = transactionType;
    this.amountMinor = amountMinor;
    this.effectiveDate = effectiveDate;
    this.reason = reason;
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

  public void reverse(Instant now, String reversalReason) {
    reversedAt = now;
    this.reversalReason = reversalReason;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getSinkingFundId() {
    return sinkingFundId;
  }

  public UUID getEntryId() {
    return entryId;
  }

  public SinkingFundTransactionType getTransactionType() {
    return transactionType;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public String getReason() {
    return reason;
  }

  public String getNotes() {
    return notes;
  }

  public Instant getReversedAt() {
    return reversedAt;
  }

  public String getReversalReason() {
    return reversalReason;
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
