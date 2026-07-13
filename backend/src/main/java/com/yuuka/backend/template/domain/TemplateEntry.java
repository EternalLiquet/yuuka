package com.yuuka.backend.template.domain;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryType;
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
@Table(name = "template_entries")
public class TemplateEntry {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "template_id", nullable = false)
  private UUID templateId;

  @Enumerated(EnumType.STRING)
  @Column(name = "entry_type", nullable = false, length = 32)
  private EntryType entryType;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_method", length = 20)
  private EntryPaymentMethod paymentMethod;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(name = "default_amount_minor", nullable = false)
  private long defaultAmountMinor;

  @Column(nullable = false)
  private int position;

  @Column(name = "default_due_offset_days")
  private Integer defaultDueOffsetDays;

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

  @Version
  @Column(nullable = false)
  private long version;

  protected TemplateEntry() {}

  public TemplateEntry(
      UUID ownerId,
      UUID templateId,
      EntryType entryType,
      String name,
      long defaultAmountMinor,
      int position,
      EntryPaymentMethod paymentMethod,
      Integer defaultDueOffsetDays,
      String accountName,
      String payee,
      String notes,
      Long targetMinor,
      LocalDate targetDate) {
    this.ownerId = ownerId;
    this.templateId = templateId;
    this.entryType = entryType;
    this.name = name;
    this.defaultAmountMinor = defaultAmountMinor;
    this.position = position;
    this.paymentMethod = paymentMethod;
    this.defaultDueOffsetDays = defaultDueOffsetDays;
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
      long defaultAmountMinor,
      EntryPaymentMethod paymentMethod,
      Integer defaultDueOffsetDays,
      String accountName,
      String payee,
      String notes,
      Long targetMinor,
      LocalDate targetDate) {
    this.entryType = entryType;
    this.name = name;
    this.defaultAmountMinor = defaultAmountMinor;
    this.paymentMethod = paymentMethod;
    this.defaultDueOffsetDays = defaultDueOffsetDays;
    this.accountName = accountName;
    this.payee = payee;
    this.notes = notes;
    this.targetMinor = targetMinor;
    this.targetDate = targetDate;
  }

  public void moveTo(int nextPosition) {
    position = nextPosition;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getTemplateId() {
    return templateId;
  }

  public EntryType getEntryType() {
    return entryType;
  }

  public EntryPaymentMethod getPaymentMethod() {
    return paymentMethod;
  }

  public String getName() {
    return name;
  }

  public long getDefaultAmountMinor() {
    return defaultAmountMinor;
  }

  public int getPosition() {
    return position;
  }

  public Integer getDefaultDueOffsetDays() {
    return defaultDueOffsetDays;
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

  public long getVersion() {
    return version;
  }
}
