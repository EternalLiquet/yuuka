package com.yuuka.backend.auth.application;

import org.springframework.security.core.AuthenticationException;

public class InvalidRefreshTokenException extends AuthenticationException {
  public InvalidRefreshTokenException() {
    super("Invalid refresh token");
  }
}
