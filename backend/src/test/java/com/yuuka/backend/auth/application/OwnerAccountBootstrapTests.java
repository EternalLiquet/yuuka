package com.yuuka.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.domain.UserRole;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.config.OwnerProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class OwnerAccountBootstrapTests {
  @Mock private JpaUserAccountRepository users;
  @Mock private PasswordEncoder passwordEncoder;

  @Test
  void updatesAnExistingOwnerWhenTheConfiguredProductionHashChanges() {
    UserAccount account = new UserAccount("owner@yuuka.local", "old-hash", "Owner", UserRole.USER);
    String configuredHash = "$2a$12$" + "a".repeat(53);
    OwnerProperties properties =
        new OwnerProperties(
            "OWNER@YUUKA.LOCAL", " " + configuredHash + " ", null, "JBSWY3DPEHPK3PXP");
    when(users.count()).thenReturn(1L);
    when(users.existsByEmailIgnoreCase("owner@yuuka.local")).thenReturn(true);
    when(users.findByEmailIgnoreCase("owner@yuuka.local")).thenReturn(Optional.of(account));

    new OwnerAccountBootstrap(properties, users, passwordEncoder).run(null);

    assertThat(account.getPasswordHash()).isEqualTo(configuredHash);
    verify(users).save(account);
  }
}
