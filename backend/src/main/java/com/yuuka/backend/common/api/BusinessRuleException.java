package com.yuuka.backend.common.api;

import java.util.Map;

public class BusinessRuleException extends RuntimeException {
  private final String code;
  private final Map<String, Object> details;

  public BusinessRuleException(String message) {
    this("BUSINESS_RULE_VIOLATION", message, Map.of());
  }

  public BusinessRuleException(String code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }

  public String code() {
    return code;
  }

  public Map<String, Object> details() {
    return details;
  }
}
