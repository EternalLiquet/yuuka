package com.yuuka.backend.mcp.tools;

import java.util.regex.Pattern;

/**
 * Exact decimal-string encoding for money and optimistic-lock versions on the MCP surface. JSON
 * numbers above 2^53 lose precision in JavaScript hosts, so these values travel as strings.
 */
public final class McpValues {
  /** JSON Schema pattern for a signed 64-bit decimal string. */
  public static final String INTEGER_STRING_PATTERN = "^-?[0-9]{1,19}$";

  private static final Pattern INTEGER_STRING = Pattern.compile("-?[0-9]{1,19}");

  private McpValues() {}

  public static String money(Long minorUnits) {
    return minorUnits == null ? null : Long.toString(minorUnits);
  }

  public static String money(long minorUnits) {
    return Long.toString(minorUnits);
  }

  public static String version(long version) {
    return Long.toString(version);
  }

  public static String count(long count) {
    return Long.toString(count);
  }

  /** Parses a decimal string; throws NumberFormatException when malformed or beyond 64 bits. */
  public static long parseInteger(String text) {
    if (text == null || !INTEGER_STRING.matcher(text).matches()) {
      throw new NumberFormatException("Not a decimal integer");
    }
    return Long.parseLong(text);
  }
}
