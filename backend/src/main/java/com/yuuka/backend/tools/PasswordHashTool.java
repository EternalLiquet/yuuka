package com.yuuka.backend.tools;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class PasswordHashTool {
  private PasswordHashTool() {}

  public static void main(String[] args) {
    String password = args.length > 0 ? args[0] : System.getenv("YUUKA_PASSWORD_TO_HASH");
    if (password == null || password.length() < 12) {
      throw new IllegalArgumentException("Provide a password of at least 12 characters");
    }

    System.out.println(new BCryptPasswordEncoder(12).encode(password));
  }
}
