package com.yuuka.backend.mcp.tools;

/** A tool-level failure, reported inside the result with {@code isError=true}. */
public class McpToolFailure extends RuntimeException {
  public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
  public static final String NOT_FOUND = "NOT_FOUND";

  private final String code;
  private final boolean retryable;

  public McpToolFailure(String code, String message, boolean retryable) {
    super(message, null, false, false);
    this.code = code;
    this.retryable = retryable;
  }

  public static McpToolFailure invalid(String message) {
    return new McpToolFailure(INVALID_ARGUMENTS, message, false);
  }

  public static McpToolFailure notFound() {
    return new McpToolFailure(NOT_FOUND, "The requested resource was not found.", false);
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
