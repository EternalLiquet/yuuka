package com.yuuka.backend.tools;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

public final class TotpSecretTool {
  private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private TotpSecretTool() {}

  public static void main(String[] args) {
    String email = args.length > 0 ? args[0] : System.getenv("YUUKA_OWNER_EMAIL");
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException(
          "Provide owner email with -Pemail=... or YUUKA_OWNER_EMAIL");
    }

    byte[] secretBytes = new byte[20];
    new SecureRandom().nextBytes(secretBytes);
    String secret = encodeBase32(secretBytes);
    String label = "Yuuka:" + email.trim().toLowerCase(Locale.ROOT);
    String uri =
        "otpauth://totp/"
            + encode(label)
            + "?secret="
            + secret
            + "&issuer=Yuuka&algorithm=SHA1&digits=6&period=30";

    System.out.println("TOTP secret: " + secret);
    System.out.println("Authenticator URI: " + uri);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String encodeBase32(byte[] bytes) {
    StringBuilder result = new StringBuilder((bytes.length * 8 + 4) / 5);
    int buffer = 0;
    int bitsLeft = 0;

    for (byte current : bytes) {
      buffer = (buffer << 8) | (current & 0xff);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        result.append(BASE32_ALPHABET[(buffer >> (bitsLeft - 5)) & 0x1f]);
        bitsLeft -= 5;
      }
    }

    if (bitsLeft > 0) {
      result.append(BASE32_ALPHABET[(buffer << (5 - bitsLeft)) & 0x1f]);
    }

    return result.toString();
  }
}
