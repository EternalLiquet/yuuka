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
@Table(name = "paychecks")
public class Paycheck {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(length = 160)
  private String source;

  @Column(name = "amount_minor", nullable = false)
  private long amountMinor;

  @Column(name = "income_date", nullable = false)
  private LocalDate incomeDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PaycheckState state = PaycheckState.ACTIVE;

  @Column(name = "template_source_id")
  private UUID templateSourceId;

  @Column(length = 2000)
  private String notes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "reopened_at")
  private Instant reopenedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected Paycheck() {}

  public Paycheck(
      UUID ownerId,
      String name,
      String source,
      long amountMinor,
      LocalDate incomeDate,
      String notes,
      UUID templateSourceId) {
    this.ownerId = ownerId;
    this.name = name;
    this.source = source;
    this.amountMinor = amountMinor;
    this.incomeDate = incomeDate;
    this.notes = notes;
    this.templateSourceId = templateSourceId;
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
      String name, String source, long amountMinor, LocalDate incomeDate, String notes) {
    this.name = name;
    this.source = source;
    this.amountMinor = amountMinor;
    this.incomeDate = incomeDate;
    this.notes = notes;
  }

  public void close(Instant now) {
    state = PaycheckState.CLOSED;
    closedAt = now;
  }

  public void reopen(Instant now) {
    state = PaycheckState.ACTIVE;
    reopenedAt = now;
    archivedAt = null;
  }

  public void archive(Instant now) {
    state = PaycheckState.ARCHIVED;
    archivedAt = now;
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

  public String getSource() {
    return source;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public LocalDate getIncomeDate() {
    return incomeDate;
  }

  public PaycheckState getState() {
    return state;
  }

  public UUID getTemplateSourceId() {
    return templateSourceId;
  }

  public String getNotes() {
    return notes;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public Instant getReopenedAt() {
    return reopenedAt;
  }

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public long getVersion() {
    return version;
  }
}
