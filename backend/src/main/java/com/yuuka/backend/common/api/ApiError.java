package com.yuuka.backend.common.api;

import java.util.Map;

public record ApiError(
    String code,
    String message,
    Map<String, String> fieldErrors,
    Map<String, Object> details,
    String traceId) {
  public ApiError {
    fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public ApiError(String code, String message, Map<String, String> fieldErrors, String traceId) {
    this(code, message, fieldErrors, Map.of(), traceId);
  }

  public static ApiError of(String code, String message, String traceId) {
    return new ApiError(code, message, Map.of(), Map.of(), traceId);
  }

  public static ApiError of(
      String code, String message, Map<String, Object> details, String traceId) {
    return new ApiError(code, message, Map.of(), details, traceId);
  }
}
