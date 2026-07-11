package com.yuuka.backend.auth.domain;

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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
public class UserAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true, length = 320)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "display_name", length = 120)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private UserRole role;

  @Column(name = "currency_code", nullable = false, length = 3)
  private String currencyCode = "USD";

  @Column(nullable = false, length = 64)
  private String timezone = "America/Indianapolis";

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserAccount() {}

  public UserAccount(String email, String passwordHash, String displayName, UserRole role) {
    this.email = email;
    this.passwordHash = passwordHash;
    this.displayName = displayName;
    this.role = role;
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

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public UserRole getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public String getTimezone() {
    return timezone;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void updatePreferences(String displayName, String currencyCode, String timezone) {
    this.displayName = displayName;
    this.currencyCode = currencyCode;
    this.timezone = timezone;
  }

  public void replacePasswordHash(String passwordHash) {
    this.passwordHash = passwordHash;
  }
}
