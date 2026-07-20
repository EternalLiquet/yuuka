package com.yuuka.backend.auth.application;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OwnerLocalDateService {
  private final JpaUserAccountRepository userAccounts;
  private final Clock clock;

  public OwnerLocalDateService(JpaUserAccountRepository userAccounts, Clock clock) {
    this.userAccounts = userAccounts;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public LocalDate currentDate(UUID ownerId) {
    return LocalDate.ofInstant(clock.instant(), zoneId(ownerId));
  }

  @Transactional(readOnly = true)
  public ZoneId zoneId(UUID ownerId) {
    UserAccount owner = userAccounts.findById(ownerId).orElseThrow(ResourceNotFoundException::new);
    return zone(owner.getTimezone());
  }

  private ZoneId zone(String timezone) {
    try {
      return ZoneId.of(timezone);
    } catch (DateTimeException exception) {
      throw new BusinessRuleException(
          "OWNER_TIMEZONE_INVALID",
          "The owner timezone is invalid.",
          Map.of("timezone", timezone == null ? "" : timezone));
    }
  }
}
