package com.yuuka.backend.auth.application;

import com.yuuka.backend.auth.api.dto.MeResponse;
import com.yuuka.backend.auth.api.dto.UpdateOwnerSettingsRequest;
import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerSettingsService {
  private final JpaUserAccountRepository users;

  public OwnerSettingsService(JpaUserAccountRepository users) {
    this.users = users;
  }

  @Transactional
  public MeResponse update(UUID ownerId, UpdateOwnerSettingsRequest request) {
    UserAccount owner = users.findById(ownerId).orElseThrow(ResourceNotFoundException::new);
    owner.updateRecurringBillSuggestionDays(request.recurringBillSuggestionDays());
    return MeResponse.from(users.saveAndFlush(owner));
  }
}
