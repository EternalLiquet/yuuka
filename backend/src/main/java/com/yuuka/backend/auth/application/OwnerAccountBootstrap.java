package com.yuuka.backend.auth.application;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.domain.UserRole;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.config.OwnerProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(0)
public class OwnerAccountBootstrap implements ApplicationRunner {
  private final OwnerProperties ownerProperties;
  private final JpaUserAccountRepository userAccounts;
  private final PasswordEncoder passwordEncoder;

  public OwnerAccountBootstrap(
      OwnerProperties ownerProperties,
      JpaUserAccountRepository userAccounts,
      PasswordEncoder passwordEncoder) {
    this.ownerProperties = ownerProperties;
    this.userAccounts = userAccounts;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!ownerProperties.hasEmail()) {
      return;
    }

    String ownerEmail = ownerProperties.normalizedEmail();
    long accountCount = userAccounts.count();
    boolean ownerExists = userAccounts.existsByEmailIgnoreCase(ownerEmail);

    if (accountCount > 0 && !ownerExists) {
      throw new IllegalStateException("Single-owner mode refuses non-owner account data");
    }

    if (accountCount > 1) {
      throw new IllegalStateException("Single-owner mode refuses multiple account records");
    }

    if (ownerExists && ownerProperties.hasPasswordHash()) {
      UserAccount owner =
          userAccounts
              .findByEmailIgnoreCase(ownerEmail)
              .orElseThrow(() -> new IllegalStateException("Owner account lookup failed"));
      String configuredHash = ownerProperties.passwordHash().trim();
      if (!configuredHash.equals(owner.getPasswordHash())) {
        owner.replacePasswordHash(configuredHash);
        userAccounts.save(owner);
      }
      return;
    }

    if (!ownerExists && hasBootstrapCredential()) {
      userAccounts.save(
          new UserAccount(ownerEmail, bootstrapPasswordHash(), "Owner", UserRole.USER));
    }
  }

  private boolean hasBootstrapCredential() {
    return ownerProperties.hasPasswordHash() || ownerProperties.hasBootstrapPassword();
  }

  private String bootstrapPasswordHash() {
    if (ownerProperties.hasPasswordHash()) {
      return ownerProperties.passwordHash().trim();
    }
    return passwordEncoder.encode(ownerProperties.bootstrapPassword());
  }
}
