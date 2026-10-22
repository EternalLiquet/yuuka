package com.yuuka.backend.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Strict argument reader: rejects unknown properties, wrong types, and out-of-range values. */
public final class McpArguments {
  private final JsonNode node;

  private McpArguments(JsonNode node) {
    this.node = node;
  }

  /** Validates the argument object and rejects every property not in {@code allowed}. */
  public static McpArguments of(JsonNode arguments, Set<String> allowed) {
    JsonNode value = arguments == null || arguments.isNull() ? null : arguments;
    if (value != null && !value.isObject()) {
      throw McpToolFailure.invalid("$: arguments must be an object.");
    }
    if (value != null) {
      Iterator<String> names = value.fieldNames();
      Set<String> unknown = new HashSet<>();
      while (names.hasNext()) {
        String name = names.next();
        if (!allowed.contains(name)) {
          unknown.add(name);
        }
      }
      if (!unknown.isEmpty()) {
        String first = unknown.stream().sorted().findFirst().orElseThrow();
        throw McpToolFailure.invalid("$." + first + ": unknown property.");
      }
    }
    return new McpArguments(value);
  }

  private JsonNode raw(String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value;
  }

  public String string(String field, boolean required, int minLength, int maxLength) {
    JsonNode value = raw(field);
    if (value == null) {
      if (required) {
        throw McpToolFailure.invalid("$." + field + ": is required.");
      }
      return null;
    }
    if (!value.isTextual()) {
      throw McpToolFailure.invalid("$." + field + ": must be a string.");
    }
    String text = value.asText();
    if (text.length() < minLength || text.length() > maxLength) {
      throw McpToolFailure.invalid(
          "$." + field + ": length must be between " + minLength + " and " + maxLength + ".");
    }
    return text;
  }

  public int integer(String field, int defaultValue, int min, int max) {
    JsonNode value = raw(field);
    if (value == null) {
      return defaultValue;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw McpToolFailure.invalid("$." + field + ": must be an integer.");
    }
    int number = value.intValue();
    if (number < min || number > max) {
      throw McpToolFailure.invalid("$." + field + ": must be between " + min + " and " + max + ".");
    }
    return number;
  }

  public <E extends Enum<E>> E enumValue(String field, Class<E> type, E defaultValue) {
    JsonNode value = raw(field);
    if (value == null) {
      if (defaultValue == null) {
        throw McpToolFailure.invalid("$." + field + ": is required.");
      }
      return defaultValue;
    }
    if (value.isTextual()) {
      for (E constant : type.getEnumConstants()) {
        if (constant.name().equals(value.asText())) {
          return constant;
        }
      }
    }
    throw McpToolFailure.invalid("$." + field + ": is not an allowed value.");
  }

  public LocalDate date(String field, boolean required) {
    JsonNode value = raw(field);
    if (value == null) {
      if (required) {
        throw McpToolFailure.invalid("$." + field + ": is required.");
      }
      return null;
    }
    if (!value.isTextual()) {
      throw McpToolFailure.invalid("$." + field + ": must be an ISO date string.");
    }
    try {
      return LocalDate.parse(value.asText());
    } catch (DateTimeParseException exception) {
      throw McpToolFailure.invalid("$." + field + ": must be an ISO date (yyyy-MM-dd).");
    }
  }

  public UUID uuid(String field) {
    JsonNode value = raw(field);
    if (value == null) {
      throw McpToolFailure.invalid("$." + field + ": is required.");
    }
    if (!value.isTextual()) {
      throw McpToolFailure.invalid("$." + field + ": must be a UUID string.");
    }
    try {
      if (value.asText().length() != 36) {
        throw new IllegalArgumentException("not canonical");
      }
      return UUID.fromString(value.asText());
    } catch (IllegalArgumentException exception) {
      throw McpToolFailure.invalid("$." + field + ": must be a UUID.");
    }
  }

  /** Reads a signed 64-bit decimal string such as an amount in minor units. */
  public Long integerString(String field) {
    JsonNode value = raw(field);
    if (value == null) {
      return null;
    }
    if (!value.isTextual()) {
      throw McpToolFailure.invalid("$." + field + ": must be a decimal string.");
    }
    try {
      return McpValues.parseInteger(value.asText());
    } catch (NumberFormatException exception) {
      throw McpToolFailure.invalid(
          "$." + field + ": must be a whole number string within the supported range.");
    }
  }
}
