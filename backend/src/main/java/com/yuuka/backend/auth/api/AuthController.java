package com.yuuka.backend.auth.api;

import com.yuuka.backend.auth.api.dto.AuthResponse;
import com.yuuka.backend.auth.api.dto.LoginRequest;
import com.yuuka.backend.auth.api.dto.LogoutRequest;
import com.yuuka.backend.auth.api.dto.RefreshRequest;
import com.yuuka.backend.auth.api.dto.RegisterRequest;
import com.yuuka.backend.auth.application.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register an account when explicitly enabled")
  public AuthResponse register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
    return authService.register(request, clientAddress(servletRequest));
  }

  @PostMapping("/login")
  @Operation(summary = "Authenticate the configured owner")
  public AuthResponse login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
    return authService.login(request, clientAddress(servletRequest));
  }

  @PostMapping("/refresh")
  @Operation(summary = "Rotate a refresh token and issue a new session")
  public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Revoke a refresh token")
  public void logout(@Valid @RequestBody LogoutRequest request) {
    authService.logout(request.refreshToken());
  }

  private String clientAddress(HttpServletRequest request) {
    return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
  }
}
