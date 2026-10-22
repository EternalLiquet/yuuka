package com.yuuka.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.yuuka.backend.mcp.support.AbstractMcpTest;
import com.yuuka.backend.mcp.support.SyntheticMcpTokens;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** The review-queue MCP tool against PostgreSQL: arguments, schema, isolation, truthfulness. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewQueueToolTests extends AbstractMcpTest {
  private static final String TOOL = "yuuka_get_review_queue";
  private static final int A = 5;
  private static final int B = 1;
  private static final String WINDOW = "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\"}";
  private static final long BIG_PAYCHECK = 9_007_199_254_740_993L;
  private static final long ALLOCATED = 100 + 50 + 10 + 4500;

  private JsonSchema outputSchema;
  private String aPaycheckId;
  private String aWaterDefinitionId;
  private String bPaycheckId;

  @BeforeAll
  void fixtures() throws Exception {
    ensureOwner(A);
    ensureOwner(B);
    aWaterDefinitionId = createDefinition(A, "Water", 15).path("id").asText();
    aPaycheckId = createPaycheck(A, "Review pay", BIG_PAYCHECK, "2026-07-01").path("id").asText();
    entry(A, aPaycheckId, "BILL", "Manual rent", 100, "MANUAL", "2020-01-01");
    JsonNode slow = entry(A, aPaycheckId, "BILL", "Slow transfer", 50, null, null);
    changeStatus(A, slow, "PROCESSING", "2020-01-01T00:00:00Z");
    JsonNode bucket = entry(A, aPaycheckId, "SPENDING_BUCKET", "Food", 10, null, null);
    rest(
        post("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
            .header("Authorization", "Bearer " + restToken(A))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"amountMinor\":500,\"description\":\"x\",\"effectiveDate\":\"2026-07-17\"}"),
        201);
    entry(A, aPaycheckId, "BILL", "water", 4500, null, "2026-07-14");

    createDefinition(B, "Other owner bill", 15);
    bPaycheckId = createPaycheck(B, "B pay", 7000, "2026-07-02").path("id").asText();
    entry(B, bPaycheckId, "BILL", "Water", 4500, "MANUAL", "2020-01-01");
  }

  // ---- helpers
  // -----------------------------------------------------------------------------------

  private JsonNode createDefinition(int owner, String name, int dueDay) throws Exception {
    return rest(
        post("/api/v1/recurring-bills")
            .header("Authorization", "Bearer " + restToken(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    Map.of(
                        "name",
                        name,
                        "amountMode",
                        "FIXED",
                        "typicalAmountMinor",
                        4500,
                        "paymentMethod",
                        "AUTOPAY",
                        "dueDay",
                        dueDay))),
        201);
  }

  private JsonNode entry(
      int owner,
      String paycheckId,
      String type,
      String name,
      long amount,
      String paymentMethod,
      String dueDate)
      throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("entryType", type);
    body.put("name", name);
    body.put("amountMinor", amount);
    if (paymentMethod != null) {
      body.put("paymentMethod", paymentMethod);
    }
    if (dueDate != null) {
      body.put("dueDate", dueDate);
    }
    return rest(
        post("/api/v1/paychecks/{id}/entries", paycheckId)
            .header("Authorization", "Bearer " + restToken(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)),
        201);
  }

  private void changeStatus(int owner, JsonNode entry, String to, String effectiveAt)
      throws Exception {
    rest(
        post("/api/v1/entries/{id}/status", entry.path("id").asText())
            .header("Authorization", "Bearer " + restToken(owner))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"toStatus\":\"%s\",\"effectiveAt\":\"%s\",\"version\":%d}"
                    .formatted(to, effectiveAt, entry.path("version").asLong())),
        200);
  }

  private JsonSchema schema() throws Exception {
    if (outputSchema == null) {
      JsonNode listed =
          objectMapper.readTree(
              mockMvc
                  .perform(
                      modernRequest(
                          mcpToken(A), "tools/list", null, modernBody(1, "tools/list", null)))
                  .andExpect(status().isOk())
                  .andReturn()
                  .getResponse()
                  .getContentAsString());
      for (JsonNode tool : listed.at("/result/tools")) {
        if (TOOL.equals(tool.path("name").asText())) {
          outputSchema =
              JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                  .getSchema(tool.path("outputSchema"));
        }
      }
    }
    return outputSchema;
  }

  /** Calls the tool and validates the structured content against the declared output schema. */
  private JsonNode call(int owner, String arguments) throws Exception {
    JsonNode result = callTool(owner, TOOL, arguments).path("result");
    JsonNode structured = result.path("structuredContent");
    Set<ValidationMessage> errors = schema().validate(structured);
    assertThat(errors).as("output schema violations").isEmpty();
    assertThat(objectMapper.readTree(result.at("/content/0/text").asText())).isEqualTo(structured);
    assertThat(structured.path("tool").asText()).isEqualTo(TOOL);
    assertThat(structured.path("bankFeed").asBoolean(true)).isFalse();
    return result;
  }

  private JsonNode ok(int owner, String arguments) throws Exception {
    JsonNode result = call(owner, arguments);
    assertThat(result.path("isError").asBoolean(true)).isFalse();
    return result.path("structuredContent");
  }

  private void invalid(int owner, String arguments) throws Exception {
    JsonNode result = call(owner, arguments);
    assertThat(result.path("isError").asBoolean(false)).isTrue();
    JsonNode envelope = result.path("structuredContent");
    assertThat(envelope.at("/failure/code").asText()).isEqualTo("INVALID_ARGUMENTS");
    assertThat(envelope.path("data").isNull()).isTrue();
    assertThat(envelope.path("page").isNull()).isTrue();
  }

  private static List<JsonNode> findings(JsonNode envelope, String check) {
    List<JsonNode> result = new ArrayList<>();
    envelope
        .at("/data/findings")
        .forEach(
            finding -> {
              if (check.equals(finding.path("checkCode").asText())) {
                result.add(finding);
              }
            });
    return result;
  }

  // ---- tests
  // ---------------------------------------------------------------------------------------

  @Test
  void returnsAnEvidenceBackedSnapshotEnvelopeWithDecimalStrings() throws Exception {
    JsonNode envelope = ok(A, WINDOW);

    assertThat(envelope.path("consistency").asText()).isEqualTo("SNAPSHOT");
    assertThat(envelope.path("truncated").asBoolean(true)).isFalse();
    assertThat(envelope.path("failure").isNull()).isTrue();
    assertThat(envelope.at("/coverage/scope").asText()).isEqualTo("budget-review-queue");
    assertThat(envelope.path("filters").path("from").asText()).isEqualTo("2026-07-01");
    assertThat(envelope.path("page").path("totalItems").isTextual()).isTrue();
    assertThat(envelope.at("/data/paycheckScope").asText()).isEqualTo("ALL_ACTIVE_PAYCHECKS");
    List<String> limitations = new ArrayList<>();
    envelope.at("/coverage/limitations").forEach(l -> limitations.add(l.asText()));
    assertThat(limitations)
        .contains(
            "No bank feed: findings are not verified against bank activity.",
            "Unsaved, unsynchronized, or unentered activity may exist.",
            "Unlinked is not unpaid; multiple links may be intentional; possible matches are not"
                + " confirmed.",
            "Occurrences come only from currently active recurring definitions.",
            "Recurring checks cover occurrences dated 2026-07-01..2026-07-31 (inclusive); paycheck"
                + " checks cover all Active paychecks regardless of date.",
            "Each page is computed in its own snapshot; separate pages may reflect different data.");

    JsonNode unallocated = findings(envelope, "PAYCHECK_UNALLOCATED").get(0);
    assertThat(unallocated.path("paycheckId").asText()).isEqualTo(aPaycheckId);
    assertThat(unallocated.at("/evidence/paycheck/amount").asText())
        .isEqualTo(Long.toString(BIG_PAYCHECK));
    assertThat(unallocated.at("/evidence/paycheck/allocated").asText())
        .isEqualTo(Long.toString(ALLOCATED));
    assertThat(unallocated.at("/evidence/paycheck/unallocated").asText())
        .isEqualTo(Long.toString(BIG_PAYCHECK - ALLOCATED));
    assertThat(unallocated.at("/evidence/paycheck/version").isTextual()).isTrue();

    JsonNode unlinked = findings(envelope, "RECURRING_OCCURRENCE_UNLINKED").get(0);
    assertThat(unlinked.path("definitionId").asText()).isEqualTo(aWaterDefinitionId);
    assertThat(unlinked.path("primaryDate").asText()).isEqualTo("2026-07-15");
    assertThat(unlinked.path("question").asText()).contains("not proof the bill is unpaid");
    assertThat(unlinked.at("/evidence/occurrence/amount").asText()).isEqualTo("4500");
    assertThat(unlinked.at("/evidence/candidateSearch/complete").asBoolean()).isTrue();
    assertThat(unlinked.at("/evidence/possibleMatches/0/entry/name").asText()).isEqualTo("water");
    assertThat(unlinked.at("/evidence/possibleMatches/0/reasons").toString())
        .contains("NAME_EQUALS_IGNORING_CASE", "AMOUNT_EQUALS_OCCURRENCE_OR_TYPICAL");

    JsonNode manual = findings(envelope, "MANUAL_BILL_NOT_PAID_DUE").get(0);
    assertThat(manual.path("severity").asText()).isEqualTo("HIGH");
    JsonNode processing = findings(envelope, "PROCESSING_AGED").get(0);
    assertThat(processing.at("/evidence/processing/effectiveAt").asText())
        .startsWith("2020-01-01T00:00:00");
    assertThat(processing.at("/evidence/processing/recordedAt").asText())
        .isNotEqualTo(processing.at("/evidence/processing/effectiveAt").asText());
    JsonNode bucket = findings(envelope, "BUCKET_OVER_BUDGET").get(0);
    assertThat(bucket.at("/evidence/bucket/overage").asText()).isEqualTo("490");

    assertThat(envelope.at("/data/summary/PAYCHECK_UNALLOCATED").asInt()).isEqualTo(1);
    assertThat(envelope.at("/data/summary/BUCKET_OVER_BUDGET").asInt()).isEqualTo(1);
    assertThat(envelope.at("/data/findings/0/severity").asText()).isEqualTo("HIGH");
  }

  @Test
  void pagingReportsTotalsForTheWholeSnapshotAndNeverClampsSize() throws Exception {
    JsonNode first = ok(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"size\":2}");
    JsonNode second =
        ok(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"size\":2,\"page\":1}");

    assertThat(first.at("/page/size").asInt()).isEqualTo(2);
    assertThat(first.at("/data/findings")).hasSize(2);
    assertThat(first.at("/page/hasNext").asBoolean()).isTrue();
    assertThat(first.at("/page/totalItems").asText())
        .isEqualTo(second.at("/page/totalItems").asText());
    assertThat(first.at("/data/summary")).isEqualTo(second.at("/data/summary"));
    assertThat(first.at("/page/totalPages").asInt()).isEqualTo(3);
    assertThat(second.at("/page/page").asInt()).isEqualTo(1);
    assertThat(second.at("/data/findings/0/checkCode").asText())
        .isNotEqualTo(first.at("/data/findings/0/checkCode").asText() + "x");
  }

  @Test
  void argumentsAreValidatedStrictlyAndNeverClamped() throws Exception {
    invalid(A, "{}");
    invalid(A, "{\"from\":\"2026-07-01\"}");
    invalid(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"extra\":1}");
    invalid(
        A,
        "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"ownerId\":\"" + ownerId(B) + "\"}");
    invalid(A, "{\"from\":\"07/01/2026\",\"through\":\"2026-07-31\"}");
    invalid(A, "{\"from\":\"2026-02-30\",\"through\":\"2026-07-31\"}");
    invalid(A, "{\"from\":\"2026-07-31\",\"through\":\"2026-07-01\"}");
    invalid(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-10-02\"}");
    invalid(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"size\":0}");
    invalid(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"size\":101}");
    invalid(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"page\":-1}");
    invalid(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-07-31\",\"size\":\"10\"}");

    // 2026-07-01..2026-10-01 is exactly 93 days inclusive.
    JsonNode accepted = ok(A, "{\"from\":\"2026-07-01\",\"through\":\"2026-10-01\",\"size\":100}");
    assertThat(accepted.at("/page/size").asInt()).isEqualTo(100);
  }

  @Test
  void otherOwnersRecordsAndTotalsAreNeverVisible() throws Exception {
    JsonNode mine = ok(A, WINDOW);
    JsonNode theirs = ok(B, WINDOW);

    assertThat(mine.toString()).doesNotContain(bPaycheckId).doesNotContain("Other owner bill");
    assertThat(theirs.toString()).doesNotContain(aPaycheckId).doesNotContain(aWaterDefinitionId);
    assertThat(theirs.toString()).doesNotContain("Review pay").doesNotContain("Manual rent");
    assertThat(theirs.at("/page/totalItems").asText())
        .isNotEqualTo(mine.at("/page/totalItems").asText());
    assertThat(
            findings(theirs, "PAYCHECK_UNALLOCATED")
                .get(0)
                .at("/evidence/paycheck/unallocated")
                .asText())
        .isEqualTo("2500");
    // The other owner's Water entry has the same name and amount but is not offered as a match.
    assertThat(
            findings(mine, "RECURRING_OCCURRENCE_UNLINKED").get(0).at("/evidence/possibleMatches"))
        .hasSize(1);
  }

  @Test
  void callingTheToolDoesNotWriteAuditEvents() throws Exception {
    Long before = jdbc.queryForObject("select count(*) from audit_events", Long.class);
    Long events = jdbc.queryForObject("select count(*) from entry_status_events", Long.class);

    ok(A, WINDOW);
    ok(B, WINDOW);

    assertThat(jdbc.queryForObject("select count(*) from audit_events", Long.class))
        .isEqualTo(before);
    assertThat(jdbc.queryForObject("select count(*) from entry_status_events", Long.class))
        .isEqualTo(events);
  }

  @Test
  void toolIsHiddenAndRefusedWithoutTheReadScope() throws Exception {
    String noScope = SyntheticMcpTokens.spec("subject-" + A).scope(null).build();
    JsonNode listed =
        objectMapper.readTree(
            mockMvc
                .perform(
                    modernRequest(noScope, "tools/list", null, modernBody(1, "tools/list", null)))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(listed.at("/result/tools")).isEmpty();

    ObjectNode params = objectMapper.createObjectNode();
    params.put("name", TOOL);
    params.set("arguments", objectMapper.readTree(WINDOW));
    MvcResult denied =
        mockMvc
            .perform(
                modernRequest(noScope, "tools/call", TOOL, modernBody(7, "tools/call", params)))
            .andExpect(status().isForbidden())
            .andReturn();
    assertThat(denied.getResponse().getContentAsString()).doesNotContain("Review pay");

    JsonNode withScope =
        objectMapper.readTree(
            mockMvc
                .perform(
                    modernRequest(
                        mcpToken(A), "tools/list", null, modernBody(1, "tools/list", null)))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(withScope.at("/result/tools").toString()).contains(TOOL);
  }
}
