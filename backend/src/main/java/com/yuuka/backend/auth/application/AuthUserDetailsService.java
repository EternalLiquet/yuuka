package com.yuuka.backend.auth.application;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AuthUserDetailsService implements UserDetailsService {
  private final JpaUserAccountRepository userAccounts;

  public AuthUserDetailsService(JpaUserAccountRepository userAccounts) {
    this.userAccounts = userAccounts;
  }

  @Override
  public UserDetails loadUserByUsername(String username) {
    UserAccount account =
        userAccounts
            .findByEmailIgnoreCase(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found"));

    return User.withUsername(account.getEmail())
        .password(account.getPasswordHash())
        .authorities(account.getRole().authority())
        .disabled(!account.isEnabled())
        .build();
  }
}
