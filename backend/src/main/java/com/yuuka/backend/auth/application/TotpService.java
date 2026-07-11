package com.yuuka.backend.auth.application;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class TotpService {
  private static final int CODE_DIGITS = 6;
  private static final long TIME_STEP_SECONDS = 30;
  private static final int ALLOWED_WINDOW_STEPS = 1;

  private final Clock clock;

  public TotpService(Clock clock) {
    this.clock = clock;
  }

  public boolean verify(String base32Secret, String code) {
    if (base32Secret == null || code == null || !code.matches("\\d{6}")) {
      return false;
    }

    long currentStep = clock.instant().getEpochSecond() / TIME_STEP_SECONDS;
    byte[] secret = decodeBase32(base32Secret);
    for (int offset = -ALLOWED_WINDOW_STEPS; offset <= ALLOWED_WINDOW_STEPS; offset++) {
      if (constantTimeEquals(code, generateCode(secret, currentStep + offset))) {
        return true;
      }
    }
    return false;
  }

  private String generateCode(byte[] secret, long counter) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      int otp = binary % 1_000_000;
      return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", otp);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to generate TOTP code", exception);
    }
  }

  private boolean constantTimeEquals(String left, String right) {
    return MessageDigest.isEqual(left.getBytes(), right.getBytes());
  }

  private byte[] decodeBase32(String value) {
    String normalized = value.replace("=", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
    ByteBuffer buffer = ByteBuffer.allocate((normalized.length() * 5) / 8);
    int currentByte = 0;
    int bitsRemaining = 0;

    for (char character : normalized.toCharArray()) {
      int next = base32Value(character);
      currentByte = (currentByte << 5) | next;
      bitsRemaining += 5;
      if (bitsRemaining >= 8) {
        buffer.put((byte) (currentByte >> (bitsRemaining - 8)));
        bitsRemaining -= 8;
      }
    }

    byte[] decoded = new byte[buffer.position()];
    buffer.flip();
    buffer.get(decoded);
    return decoded;
  }

  private int base32Value(char character) {
    if (character >= 'A' && character <= 'Z') {
      return character - 'A';
    }
    if (character >= '2' && character <= '7') {
      return character - '2' + 26;
    }
    throw new IllegalStateException("TOTP secret must be Base32 encoded");
  }
}
