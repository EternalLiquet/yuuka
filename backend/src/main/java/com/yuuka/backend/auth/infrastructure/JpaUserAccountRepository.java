package com.yuuka.backend.auth.infrastructure;

import com.yuuka.backend.auth.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaUserAccountRepository extends JpaRepository<UserAccount, UUID> {
  boolean existsByEmailIgnoreCase(String email);

  Optional<UserAccount> findByEmailIgnoreCase(String email);
}
