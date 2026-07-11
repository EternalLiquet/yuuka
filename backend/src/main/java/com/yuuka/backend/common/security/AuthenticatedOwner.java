package com.yuuka.backend.common.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class AuthenticatedOwner {
  private AuthenticatedOwner() {}

  public static UUID id(Jwt jwt) {
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Authenticated subject is invalid", exception);
    }
  }
}
