package com.yuuka.backend.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuuka.backend.auth.api.dto.AuthResponse;
import com.yuuka.backend.auth.api.dto.LoginRequest;
import com.yuuka.backend.auth.api.dto.RegisterRequest;
import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.domain.UserRole;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.config.AuthProperties;
import com.yuuka.backend.common.config.OwnerProperties;
import com.yuuka.backend.common.security.JwtTokenService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {
  @Mock private AuthenticationManager authenticationManager;
  @Mock private JpaUserAccountRepository users;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private JwtTokenService jwtTokens;
  @Mock private RefreshTokenService refreshTokens;
  @Mock private AuthRateLimitService rateLimits;
  @Mock private TotpService totp;

  private final OwnerProperties owner =
      new OwnerProperties("owner@yuuka.local", null, null, "JBSWY3DPEHPK3PXP");
  private AuthService service;

  @BeforeEach
  void setUp() {
    service =
        new AuthService(
            authenticationManager,
            users,
            passwordEncoder,
            jwtTokens,
            refreshTokens,
            rateLimits,
            new AuthProperties(false),
            owner,
            totp);
  }

  @Test
  void authenticatesTheConfiguredOwnerWithTotpAndCreatesBothTokens() {
    UserAccount account = new UserAccount("owner@yuuka.local", "hash", "Owner", UserRole.USER);
    Instant accessExpiry = Instant.parse("2026-07-10T12:15:00Z");
    Instant refreshExpiry = Instant.parse("2026-08-09T12:00:00Z");
    when(authenticationManager.authenticate(any()))
        .thenReturn(org.mockito.Mockito.mock(Authentication.class));
    when(totp.verify("JBSWY3DPEHPK3PXP", "123456")).thenReturn(true);
    when(users.findByEmailIgnoreCase("owner@yuuka.local")).thenReturn(Optional.of(account));
    when(jwtTokens.createAccessToken(account))
        .thenReturn(new JwtTokenService.AccessToken("access", accessExpiry));
    when(refreshTokens.issue(account.getId()))
        .thenReturn(
            new RefreshTokenService.IssuedRefreshToken(
                UUID.randomUUID(), "refresh", refreshExpiry));

    AuthResponse response =
        service.login(
            new LoginRequest(" OWNER@YUUKA.LOCAL ", "Password12345", "123456"), "192.0.2.1");

    assertThat(response.accessToken()).isEqualTo("access");
    assertThat(response.refreshToken()).isEqualTo("refresh");
    verify(rateLimits).clear("email:owner@yuuka.local");
    verify(rateLimits).clear("client:192.0.2.1");
  }

  @Test
  void rejectsMissingOrInvalidTotpAndRecordsBothRateLimitKeys() {
    when(authenticationManager.authenticate(any()))
        .thenReturn(org.mockito.Mockito.mock(Authentication.class));
    when(totp.verify("JBSWY3DPEHPK3PXP", null)).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("owner@yuuka.local", "Password12345", null), "192.0.2.2"))
        .isInstanceOf(BadCredentialsException.class);

    verify(rateLimits).recordAttempt("email:owner@yuuka.local");
    verify(rateLimits).recordAttempt("client:192.0.2.2");
  }

  @Test
  void rejectsAnyEmailOtherThanTheConfiguredOwnerBeforePasswordAuthentication() {
    assertThatThrownBy(
            () ->
                service.login(
                    new LoginRequest("other@yuuka.local", "Password12345", "123456"), "192.0.2.3"))
        .isInstanceOf(BadCredentialsException.class);

    verify(authenticationManager, never()).authenticate(any());
    verify(rateLimits).recordAttempt("email:other@yuuka.local");
    verify(rateLimits).recordAttempt("client:192.0.2.3");
  }

  @Test
  void rejectsDuplicateRegistrationAndRecordsTheClientKey() {
    AuthService registrationService = registrationEnabledService();
    when(users.existsByEmailIgnoreCase("owner@yuuka.local")).thenReturn(true);

    assertThatThrownBy(
            () ->
                registrationService.register(
                    new RegisterRequest(" OWNER@YUUKA.LOCAL ", "Password12345", "Owner"),
                    "192.0.2.4"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verify(rateLimits).recordAttempt("client:192.0.2.4");
  }

  @Test
  void registersWhenEnabledAndNormalizesBlankDisplayNames() {
    AuthService registrationService = registrationEnabledService();
    Instant accessExpiry = Instant.parse("2026-07-10T12:15:00Z");
    Instant refreshExpiry = Instant.parse("2026-08-09T12:00:00Z");
    when(users.existsByEmailIgnoreCase("new@yuuka.local")).thenReturn(false);
    when(passwordEncoder.encode("Password12345")).thenReturn("hash");
    when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(jwtTokens.createAccessToken(any()))
        .thenReturn(new JwtTokenService.AccessToken("access", accessExpiry));
    when(refreshTokens.issue(any()))
        .thenReturn(
            new RefreshTokenService.IssuedRefreshToken(
                UUID.randomUUID(), "refresh", refreshExpiry));

    AuthResponse response =
        registrationService.register(
            new RegisterRequest(" NEW@YUUKA.LOCAL ", "Password12345", " "), "192.0.2.5");

    assertThat(response.accessToken()).isEqualTo("access");
    assertThat(response.refreshToken()).isEqualTo("refresh");
    verify(rateLimits).clear("client:192.0.2.5");
  }

  private AuthService registrationEnabledService() {
    return new AuthService(
        authenticationManager,
        users,
        passwordEncoder,
        jwtTokens,
        refreshTokens,
        rateLimits,
        new AuthProperties(true),
        owner,
        totp);
  }
}
