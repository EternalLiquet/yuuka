package com.yuuka.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.api.BusinessRuleException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerLocalDateServiceTests {
  private final UUID ownerId = UUID.randomUUID();
  private final JpaUserAccountRepository userAccounts = mock(JpaUserAccountRepository.class);

  @Test
  void usesOwnerTimezoneInsteadOfUtcDate() {
    OwnerLocalDateService service =
        new OwnerLocalDateService(
            userAccounts, Clock.fixed(Instant.parse("2026-07-15T02:00:00Z"), ZoneOffset.UTC));
    UserAccount owner = owner("America/Indianapolis");
    when(userAccounts.findById(ownerId)).thenReturn(Optional.of(owner));

    assertThat(service.currentDate(ownerId)).isEqualTo(LocalDate.parse("2026-07-14"));
  }

  @Test
  void advancesWhenOwnerLocalDateAdvances() {
    OwnerLocalDateService service =
        new OwnerLocalDateService(
            userAccounts, Clock.fixed(Instant.parse("2026-07-15T13:00:00Z"), ZoneOffset.UTC));
    UserAccount owner = owner("America/Indianapolis");
    when(userAccounts.findById(ownerId)).thenReturn(Optional.of(owner));

    assertThat(service.currentDate(ownerId)).isEqualTo(LocalDate.parse("2026-07-15"));
  }

  @Test
  void rejectsInvalidPersistedTimezoneWithoutFallingBackToUtc() {
    OwnerLocalDateService service =
        new OwnerLocalDateService(
            userAccounts, Clock.fixed(Instant.parse("2026-07-15T02:00:00Z"), ZoneOffset.UTC));
    UserAccount owner = owner("Not/AZone");
    when(userAccounts.findById(ownerId)).thenReturn(Optional.of(owner));

    assertThatThrownBy(() -> service.currentDate(ownerId))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessage("The owner timezone is invalid.");
  }

  private UserAccount owner(String timezone) {
    UserAccount owner = mock(UserAccount.class);
    when(owner.getTimezone()).thenReturn(timezone);
    return owner;
  }
}
