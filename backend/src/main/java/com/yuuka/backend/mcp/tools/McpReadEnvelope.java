package com.yuuka.backend.mcp.tools;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Common structured result wrapper for every MCP read tool. */
public record McpReadEnvelope<T>(
    String schemaVersion,
    String tool,
    Instant readStartedAt,
    Instant readCompletedAt,
    LocalDate asOfDate,
    String timeZone,
    String currency,
    String consistency,
    Coverage coverage,
    Map<String, Object> filters,
    Page page,
    boolean truncated,
    Failure failure,
    boolean bankFeed,
    T data) {
  public static final String SCHEMA_VERSION = "1";
  public static final String MULTI_READ = "MULTI_READ";
  public static final String SNAPSHOT = "SNAPSHOT";

  public record Coverage(String scope, List<String> limitations) {}

  public record Page(int page, int size, String totalItems, int totalPages, boolean hasNext) {}

  public record Failure(String code, String message, boolean retryable) {}
}
