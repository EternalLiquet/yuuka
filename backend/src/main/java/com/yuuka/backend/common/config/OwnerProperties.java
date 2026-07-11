package com.yuuka.backend.common.config;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yuuka.owner")
public record OwnerProperties(
    String email, String passwordHash, String bootstrapPassword, String totpSecret) {
  public OwnerProperties {
    if (passwordHash != null
        && !passwordHash.isBlank()
        && !passwordHash.trim().matches("^\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}$")) {
      throw new IllegalStateException("YUUKA_OWNER_PASSWORD_HASH must be a BCrypt cost-12 hash");
    }
    if (totpSecret != null && !totpSecret.isBlank()) {
      totpSecret = totpSecret.replace(" ", "").trim().toUpperCase(Locale.ROOT);
      if (totpSecret.length() < 16 || !totpSecret.matches("^[A-Z2-7]+=*$")) {
        throw new IllegalStateException("YUUKA_OWNER_TOTP_SECRET must be Base32 encoded");
      }
    }
  }

  public boolean hasEmail() {
    return email != null && !email.isBlank();
  }

  public String normalizedEmail() {
    return hasEmail() ? email.trim().toLowerCase(Locale.ROOT) : "";
  }

  public boolean hasPasswordHash() {
    return passwordHash != null && !passwordHash.isBlank();
  }

  public boolean hasBootstrapPassword() {
    return bootstrapPassword != null && !bootstrapPassword.isBlank();
  }

  public boolean hasTotpSecret() {
    return totpSecret != null && !totpSecret.isBlank();
  }
}
