package com.yuuka.backend.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small builders for JSON Schema 2020-12 documents used by tool input and output schemas. */
public final class McpSchemas {
  public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private McpSchemas() {}

  /** Object schema builder; unknown properties are always rejected. */
  public static final class ObjectSchema {
    private final ObjectNode properties = F.objectNode();
    private final ArrayNode required = F.arrayNode();
    private String description;

    public ObjectSchema required(String name, JsonNode schema) {
      properties.set(name, schema);
      required.add(name);
      return this;
    }

    public ObjectSchema optional(String name, JsonNode schema) {
      properties.set(name, schema);
      return this;
    }

    public ObjectSchema describe(String text) {
      this.description = text;
      return this;
    }

    public ObjectNode build() {
      ObjectNode node = F.objectNode();
      node.put("type", "object");
      if (description != null) {
        node.put("description", description);
      }
      node.set("properties", properties);
      if (!required.isEmpty()) {
        node.set("required", required);
      }
      node.put("additionalProperties", false);
      return node;
    }
  }

  public static ObjectSchema object() {
    return new ObjectSchema();
  }

  /** Top-level input schema. */
  public static ObjectNode root(ObjectNode schema) {
    ObjectNode root = F.objectNode();
    root.put("$schema", DIALECT);
    root.setAll(schema);
    return root;
  }

  public static ObjectNode string() {
    return F.objectNode().put("type", "string");
  }

  public static ObjectNode string(String description) {
    return string().put("description", description);
  }

  public static ObjectNode nullableString() {
    ObjectNode node = F.objectNode();
    node.set("type", F.arrayNode().add("string").add("null"));
    return node;
  }

  public static ObjectNode enumString(String... values) {
    ObjectNode node = string();
    ArrayNode array = node.putArray("enum");
    for (String value : values) {
      array.add(value);
    }
    return node;
  }

  public static <E extends Enum<E>> ObjectNode enumOf(Class<E> type) {
    E[] constants = type.getEnumConstants();
    String[] names = new String[constants.length];
    for (int i = 0; i < constants.length; i++) {
      names[i] = constants[i].name();
    }
    return enumString(names);
  }

  public static ObjectNode nullable(ObjectNode schema) {
    ObjectNode copy = schema.deepCopy();
    JsonNode type = copy.get("type");
    ArrayNode types = F.arrayNode();
    if (type != null && type.isTextual()) {
      types.add(type.asText()).add("null");
    } else if (type != null && type.isArray()) {
      types.addAll((ArrayNode) type).add("null");
    } else {
      return F.objectNode()
          .set("anyOf", F.arrayNode().add(copy).add(F.objectNode().put("type", "null")));
    }
    copy.set("type", types);
    if (copy.has("enum")) {
      ((ArrayNode) copy.get("enum")).addNull();
    }
    return copy;
  }

  /** Exact decimal string for money or versions. */
  public static ObjectNode integerString() {
    return string().put("pattern", McpValues.INTEGER_STRING_PATTERN);
  }

  public static ObjectNode nullableIntegerString() {
    return nullable(integerString());
  }

  public static ObjectNode uuid() {
    return string().put("format", "uuid");
  }

  public static ObjectNode nullableUuid() {
    return nullable(uuid());
  }

  public static ObjectNode date() {
    return string().put("format", "date");
  }

  public static ObjectNode nullableDate() {
    return nullable(date());
  }

  public static ObjectNode instant() {
    return string().put("format", "date-time");
  }

  public static ObjectNode nullableInstant() {
    return nullable(instant());
  }

  public static ObjectNode integer() {
    return F.objectNode().put("type", "integer");
  }

  public static ObjectNode integer(int minimum, int maximum, int defaultValue) {
    return integer().put("minimum", minimum).put("maximum", maximum).put("default", defaultValue);
  }

  public static ObjectNode number() {
    return F.objectNode().put("type", "number");
  }

  public static ObjectNode bool() {
    return F.objectNode().put("type", "boolean");
  }

  public static ObjectNode nullableBool() {
    return nullable(bool());
  }

  public static ObjectNode array(JsonNode items) {
    ObjectNode node = F.objectNode();
    node.put("type", "array");
    node.set("items", items);
    return node;
  }

  /** The common envelope with {@code data} constrained to {@code dataSchema} or null. */
  public static ObjectNode envelope(ObjectNode dataSchema) {
    ObjectNode data = F.objectNode();
    data.set("anyOf", F.arrayNode().add(dataSchema).add(F.objectNode().put("type", "null")));
    ObjectNode page =
        object()
            .required("page", integer())
            .required("size", integer())
            .required("totalItems", integerString())
            .required("totalPages", integer())
            .required("hasNext", bool())
            .build();
    ObjectNode failure =
        object()
            .required("code", string())
            .required("message", string())
            .required("retryable", bool())
            .build();
    ObjectNode coverage =
        object().required("scope", string()).required("limitations", array(string())).build();
    ObjectNode filters = F.objectNode().put("type", "object");
    return root(
        object()
            .required("schemaVersion", F.objectNode().put("const", "1"))
            .required("tool", string())
            .required("readStartedAt", instant())
            .required("readCompletedAt", instant())
            .required("asOfDate", date())
            .required("timeZone", string())
            .required("currency", string())
            .required("consistency", enumString("MULTI_READ", "SNAPSHOT"))
            .required("coverage", coverage)
            .required("filters", filters)
            .required("page", nullable(page))
            .required("truncated", bool())
            .required("failure", nullable(failure))
            .required("bankFeed", F.objectNode().put("const", false))
            .required("data", data)
            .build());
  }
}
