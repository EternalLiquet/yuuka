package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.mcp.tools.McpArguments;
import com.yuuka.backend.mcp.tools.McpReadEnvelope;
import com.yuuka.backend.mcp.tools.McpToolFailure;
import com.yuuka.backend.mcp.tools.McpValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpValuesAndEnvelopeTests {
  private final ObjectMapper mapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void valuesBeyondJavaScriptSafeIntegerSurviveExactly() {
    assertThat(McpValues.money(9007199254740993L)).isEqualTo("9007199254740993");
    assertThat(McpValues.money(Long.MAX_VALUE)).isEqualTo("9223372036854775807");
    assertThat(McpValues.money(Long.MIN_VALUE)).isEqualTo("-9223372036854775808");
    assertThat(McpValues.version(Long.MAX_VALUE)).isEqualTo("9223372036854775807");
    assertThat(McpValues.money((Long) null)).isNull();
    assertThat(McpValues.parseInteger("-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
    assertThat(McpValues.parseInteger("9007199254740993")).isEqualTo(9007199254740993L);
  }

  @Test
  void parseRejectsMalformedAndOverflowingText() {
    for (String bad :
        List.of(
            "",
            "+1",
            "1.0",
            "1e3",
            " 1",
            "1 ",
            "12345678901234567890",
            "9223372036854775808",
            "abc",
            "--1")) {
      assertThatThrownBy(() -> McpValues.parseInteger(bad))
          .isInstanceOf(NumberFormatException.class);
    }
    assertThatThrownBy(() -> McpValues.parseInteger(null))
        .isInstanceOf(NumberFormatException.class);
  }

  @Test
  void envelopeSerializesExactStringsNullsAndFieldNames() throws Exception {
    record Data(String amount, String version, String unknown) {}
    McpReadEnvelope<Data> envelope =
        new McpReadEnvelope<>(
            "1",
            "tool",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:01Z"),
            LocalDate.parse("2026-01-01"),
            "UTC",
            "USD",
            McpReadEnvelope.MULTI_READ,
            new McpReadEnvelope.Coverage("scope", List.of("limit")),
            Map.of(),
            new McpReadEnvelope.Page(0, 25, McpValues.money(Long.MAX_VALUE), 1, false),
            false,
            null,
            false,
            new Data(McpValues.money(9007199254740993L), McpValues.version(Long.MIN_VALUE), null));
    JsonNode json = mapper.readTree(mapper.writeValueAsString(envelope));
    assertThat(json.fieldNames())
        .toIterable()
        .containsExactly(
            "schemaVersion",
            "tool",
            "readStartedAt",
            "readCompletedAt",
            "asOfDate",
            "timeZone",
            "currency",
            "consistency",
            "coverage",
            "filters",
            "page",
            "truncated",
            "failure",
            "bankFeed",
            "data");
    assertThat(json.at("/data/amount").isTextual()).isTrue();
    assertThat(json.at("/data/amount").asText()).isEqualTo("9007199254740993");
    assertThat(json.at("/data/version").asText()).isEqualTo("-9223372036854775808");
    assertThat(json.at("/data/unknown").isNull()).isTrue();
    assertThat(json.at("/page/totalItems").asText()).isEqualTo("9223372036854775807");
    assertThat(json.at("/consistency").asText()).isEqualTo("MULTI_READ");
    assertThat(json.at("/bankFeed").asBoolean(true)).isFalse();
  }

  @Test
  void argumentsRejectUnknownPropertiesWrongTypesAndRanges() throws Exception {
    Set<String> allowed = Set.of("size", "text", "id", "day", "amount", "kind");
    ObjectNode unknown = (ObjectNode) mapper.readTree("{\"surprise\":1}");
    assertThatThrownBy(() -> McpArguments.of(unknown, allowed))
        .isInstanceOfSatisfying(
            McpToolFailure.class,
            f -> {
              assertThat(f.code()).isEqualTo("INVALID_ARGUMENTS");
              assertThat(f.getMessage()).contains("$.surprise");
            });
    assertThatThrownBy(() -> McpArguments.of(mapper.readTree("[]"), allowed))
        .isInstanceOf(McpToolFailure.class);
    assertThat(McpArguments.of(null, allowed).integer("size", 25, 1, 100)).isEqualTo(25);
    assertThat(McpArguments.of(mapper.createObjectNode(), allowed).integer("size", 25, 1, 100))
        .isEqualTo(25);
    for (String bad : List.of("0", "101", "\"5\"", "5.5", "true", "99999999999")) {
      McpArguments args = McpArguments.of(mapper.readTree("{\"size\":" + bad + "}"), allowed);
      assertThatThrownBy(() -> args.integer("size", 25, 1, 100)).isInstanceOf(McpToolFailure.class);
    }
    assertThat(
            McpArguments.of(mapper.readTree("{\"size\":100}"), allowed).integer("size", 25, 1, 100))
        .isEqualTo(100);
    McpArguments strings =
        McpArguments.of(
            mapper.readTree(
                "{\"text\":5,\"id\":\"nope\",\"day\":\"2026-02-30\",\"amount\":12,\"kind\":\"x\"}"),
            allowed);
    assertThatThrownBy(() -> strings.string("text", false, 1, 5))
        .isInstanceOf(McpToolFailure.class);
    assertThatThrownBy(() -> strings.uuid("id")).isInstanceOf(McpToolFailure.class);
    assertThatThrownBy(() -> strings.date("day", true)).isInstanceOf(McpToolFailure.class);
    assertThatThrownBy(() -> strings.integerString("amount")).isInstanceOf(McpToolFailure.class);
    assertThatThrownBy(() -> strings.enumValue("kind", Thread.State.class, null))
        .isInstanceOf(McpToolFailure.class);
  }
}
