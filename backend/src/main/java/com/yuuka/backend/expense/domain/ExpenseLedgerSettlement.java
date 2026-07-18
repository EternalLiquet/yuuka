package com.yuuka.backend.expense.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "expense_ledger_settlements")
public class ExpenseLedgerSettlement {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "ledger_id", nullable = false)
  private UUID ledgerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "settlement_type", nullable = false, length = 20)
  private ExpenseLedgerSettlementType settlementType;

  @Column(name = "settlement_amount_minor", nullable = false)
  private long settlementAmountMinor;

  @Column(name = "target_id", nullable = false)
  private UUID targetId;

  @Column(name = "target_paycheck_id")
  private UUID targetPaycheckId;

  @Column(name = "settled_at", nullable = false)
  private Instant settledAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ExpenseLedgerSettlement() {}

  public ExpenseLedgerSettlement(
      UUID ownerId,
      UUID ledgerId,
      ExpenseLedgerSettlementType settlementType,
      long settlementAmountMinor,
      UUID targetId,
      UUID targetPaycheckId,
      Instant settledAt) {
    this.ownerId = ownerId;
    this.ledgerId = ledgerId;
    this.settlementType = settlementType;
    this.settlementAmountMinor = settlementAmountMinor;
    this.targetId = targetId;
    this.targetPaycheckId = targetPaycheckId;
    this.settledAt = settledAt;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
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

  public ExpenseLedgerSettlementType getSettlementType() {
    return settlementType;
  }

  public long getSettlementAmountMinor() {
    return settlementAmountMinor;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public UUID getTargetPaycheckId() {
    return targetPaycheckId;
  }

  public Instant getSettledAt() {
    return settledAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
