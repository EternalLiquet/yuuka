package com.yuuka.backend.common.api;

import java.util.Map;

public record ApiError(
    String code, String message, Map<String, String> fieldErrors, String traceId) {
  public ApiError {
    fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
  }

  public static ApiError of(String code, String message, String traceId) {
    return new ApiError(code, message, Map.of(), traceId);
  }
}
