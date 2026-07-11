package com.yuuka.backend.auth.application;

import com.yuuka.backend.auth.api.dto.AuthResponse;
import com.yuuka.backend.auth.api.dto.LoginRequest;
import com.yuuka.backend.auth.api.dto.RegisterRequest;
import com.yuuka.backend.auth.domain.UserAccount;
import com.yuuka.backend.auth.domain.UserRole;
import com.yuuka.backend.auth.infrastructure.JpaUserAccountRepository;
import com.yuuka.backend.common.config.AuthProperties;
import com.yuuka.backend.common.config.OwnerProperties;
import com.yuuka.backend.common.security.JwtTokenService;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
  private final AuthenticationManager authenticationManager;
  private final JpaUserAccountRepository userAccounts;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;
  private final RefreshTokenService refreshTokenService;
  private final AuthRateLimitService authRateLimitService;
  private final AuthProperties authProperties;
  private final OwnerProperties ownerProperties;
  private final TotpService totpService;

  public AuthService(
      AuthenticationManager authenticationManager,
      JpaUserAccountRepository userAccounts,
      PasswordEncoder passwordEncoder,
      JwtTokenService jwtTokenService,
      RefreshTokenService refreshTokenService,
      AuthRateLimitService authRateLimitService,
      AuthProperties authProperties,
      OwnerProperties ownerProperties,
      TotpService totpService) {
    this.authenticationManager = authenticationManager;
    this.userAccounts = userAccounts;
    this.passwordEncoder = passwordEncoder;
    this.jwtTokenService = jwtTokenService;
    this.refreshTokenService = refreshTokenService;
    this.authRateLimitService = authRateLimitService;
    this.authProperties = authProperties;
    this.ownerProperties = ownerProperties;
    this.totpService = totpService;
  }

  @Transactional
  public AuthResponse register(RegisterRequest request, String clientAddress) {
    String email = normalizeEmail(request.email());
    String clientKey = clientRateLimitKey(clientAddress);
    authRateLimitService.assertAllowed(clientKey);

    if (!authProperties.registrationEnabled()) {
      authRateLimitService.recordAttempt(clientKey);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    if (userAccounts.existsByEmailIgnoreCase(email)) {
      authRateLimitService.recordAttempt(clientKey);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Unable to register with provided credentials.");
    }

    UserAccount account =
        userAccounts.saveAndFlush(
            new UserAccount(
                email,
                passwordEncoder.encode(request.password()),
                normalizeOptional(request.displayName()),
                UserRole.USER));
    authRateLimitService.clear(clientKey);
    return createSession(account);
  }

  @Transactional
  public AuthResponse login(LoginRequest request, String clientAddress) {
    String email = normalizeEmail(request.email());
    String emailKey = emailRateLimitKey(email);
    String clientKey = clientRateLimitKey(clientAddress);
    authRateLimitService.assertAllowed(emailKey);
    authRateLimitService.assertAllowed(clientKey);

    try {
      assertOwnerEmail(email);
      authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(email, request.password()));
      assertTotpCode(request.totpCode());
    } catch (AuthenticationException exception) {
      authRateLimitService.recordAttempt(emailKey);
      authRateLimitService.recordAttempt(clientKey);
      throw exception;
    }

    UserAccount account =
        userAccounts
            .findByEmailIgnoreCase(email)
            .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
    authRateLimitService.clear(emailKey);
    authRateLimitService.clear(clientKey);
    return createSession(account);
  }

  @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
  public AuthResponse refresh(String rawRefreshToken) {
    RefreshTokenService.RotatedRefreshToken rotated = refreshTokenService.rotate(rawRefreshToken);
    UserAccount account =
        userAccounts
            .findById(rotated.userId())
            .filter(UserAccount::isEnabled)
            .orElseThrow(InvalidRefreshTokenException::new);
    JwtTokenService.AccessToken access = jwtTokenService.createAccessToken(account);
    return new AuthResponse(
        access.value(), "Bearer", access.expiresAt(), rotated.rawToken(), rotated.expiresAt());
  }

  public void logout(String rawRefreshToken) {
    refreshTokenService.revoke(rawRefreshToken);
  }

  private AuthResponse createSession(UserAccount account) {
    JwtTokenService.AccessToken access = jwtTokenService.createAccessToken(account);
    RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(account.getId());
    return new AuthResponse(
        access.value(),
        accessTokenType(),
        access.expiresAt(),
        refresh.rawToken(),
        refresh.expiresAt());
  }

  private String accessTokenType() {
    return "Bearer";
  }

  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String emailRateLimitKey(String email) {
    return "email:" + email;
  }

  private String clientRateLimitKey(String clientAddress) {
    return "client:" + clientAddress;
  }

  private void assertOwnerEmail(String email) {
    if (ownerProperties.hasEmail() && !email.equals(ownerProperties.normalizedEmail())) {
      throw new BadCredentialsException("Invalid email or password");
    }
  }

  private void assertTotpCode(String code) {
    if (ownerProperties.hasTotpSecret()
        && !totpService.verify(ownerProperties.totpSecret(), code)) {
      throw new BadCredentialsException("Invalid email or password");
    }
  }
}
