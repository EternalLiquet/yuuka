package com.yuuka.backend.auth.domain;

public enum UserRole {
  USER;

  public String authority() {
    return "ROLE_" + name();
  }
}
