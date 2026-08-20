package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class RecurringBillReconciliationWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void linksChangesAndUnlinksTheSameBillWhilePreservingIdentityStatusAndHistory() throws Exception {
    String token = register("recurring-reconcile@yuuka.local");
    JsonNode definition =
        createDefinition(token, "Netflix", 1499, 21, "AUTOPAY", "Visa", "Netflix Inc", "Streaming");
    JsonNode second =
        createDefinition(token, "Power", 12000, 31, "MANUAL", "Checking", "Utility", null);
    JsonNode paycheck = createPaycheck(token, "Utilities 2/2", 30000, "2026-08-15");
    JsonNode entry = addBill(token, paycheck, "Netflix Subscription", 1399, "2026-08-18", "MANUAL");
    entry = changeStatus(token, entry, "PROCESSING");
    paycheck = getPaycheck(token, paycheck.path("id").asText());
    long historyBefore = statusHistoryCount(entry.path("id").asText());

    JsonNode linked = link(token, paycheck, entry, definition, "2026-08-21", false, 200);
    JsonNode linkedEntry = linked.path("entries").get(0);
    assertThat(linkedEntry.path("id").asText()).isEqualTo(entry.path("id").asText());
    assertThat(linkedEntry.path("paycheckId").asText()).isEqualTo(paycheck.path("id").asText());
    assertThat(linkedEntry.path("position").asInt()).isEqualTo(entry.path("position").asInt());
    assertThat(linkedEntry.path("status").asText()).isEqualTo("PROCESSING");
    assertThat(linkedEntry.path("name").asText()).isEqualTo("Netflix");
    assertThat(linkedEntry.path("amountMinor").asLong()).isEqualTo(1499);
    assertThat(linkedEntry.path("paymentMethod").asText()).isEqualTo("AUTOPAY");
    assertThat(linkedEntry.path("dueDate").asText()).isEqualTo("2026-08-21");
    assertThat(linkedEntry.path("accountName").asText()).isEqualTo("Visa");
    assertThat(linkedEntry.path("payee").asText()).isEqualTo("Netflix Inc");
    assertThat(linkedEntry.path("notes").asText()).isEqualTo("Streaming");
    assertThat(linkedEntry.path("sourceRecurringBillDefinitionId").asText())
        .isEqualTo(definition.path("id").asText());
    assertThat(linked.path("unallocatedMinor").asLong()).isEqualTo(28501);
    assertThat(statusHistoryCount(entry.path("id").asText())).isEqualTo(historyBefore);
    assertThat(auditCount(entry.path("id").asText(), "RECURRING_BILL_LINKED")).isEqualTo(1);

    JsonNode changed = link(token, linked, linkedEntry, second, "2026-08-31", false, 200);
    JsonNode changedEntry = changed.path("entries").get(0);
    assertThat(changedEntry.path("id").asText()).isEqualTo(entry.path("id").asText());
    assertThat(changedEntry.path("name").asText()).isEqualTo("Power");
    assertThat(changedEntry.path("dueDate").asText()).isEqualTo("2026-08-31");
    assertThat(changedEntry.path("status").asText()).isEqualTo("PROCESSING");
    assertThat(auditCount(entry.path("id").asText(), "RECURRING_BILL_LINK_CHANGED")).isEqualTo(1);

    JsonNode unlinked = unlink(token, changed, changedEntry, 200);
    JsonNode unlinkedEntry = unlinked.path("entries").get(0);
    assertThat(unlinkedEntry.path("sourceRecurringBillDefinitionId").isNull()).isTrue();
    assertThat(unlinkedEntry.path("sourceRecurringOccurrenceDate").isNull()).isTrue();
    assertThat(unlinkedEntry.path("name").asText()).isEqualTo("Power");
    assertThat(unlinkedEntry.path("amountMinor").asLong()).isEqualTo(12000);
    assertThat(unlinkedEntry.path("dueDate").asText()).isEqualTo("2026-08-31");
    assertThat(statusHistoryCount(entry.path("id").asText())).isEqualTo(historyBefore);
    assertThat(auditCount(entry.path("id").asText(), "RECURRING_BILL_LINK_REMOVED")).isEqualTo(1);

    mockMvc
        .perform(
            get("/api/v1/recurring-bills/timeline?from=2026-08-31&through=2026-08-31")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].importCount").value(0));
  }

  @Test
  void validatesVersionsOwnershipDefinitionStateOccurrenceAndAllocationAtomically()
      throws Exception {
    String token = register("recurring-validation@yuuka.local");
    String other = register("recurring-validation-other@yuuka.local");
    JsonNode definition = createDefinition(token, "Rent", 9000, 21, "AUTOPAY", null, null, null);
    JsonNode foreign = createDefinition(other, "Foreign", 1000, 21, "AUTOPAY", null, null, null);
    JsonNode paycheck = createPaycheck(token, "Small", 10000, "2026-08-15");
    JsonNode entry = addBill(token, paycheck, "Legacy", 5000, "2026-08-18", "AUTOPAY");
    paycheck = getPaycheck(token, paycheck.path("id").asText());

    link(token, paycheck, entry, definition, "2026-08-21", false, 200);
    assertThat(entryRow(entry.path("id").asText()).get("name")).isEqualTo("Rent");

    JsonNode current = getPaycheck(token, paycheck.path("id").asText());
    JsonNode currentEntry = current.path("entries").get(0);
    JsonNode tooLarge =
        createDefinition(token, "Too large", 11000, 21, "AUTOPAY", null, null, null);
    link(token, current, currentEntry, tooLarge, "2026-08-21", false, 422);
    assertThat(entryRow(entry.path("id").asText()).get("name")).isEqualTo("Rent");

    link(token, current, currentEntry, foreign, "2026-08-21", false, 404);
    link(token, current, currentEntry, definition, "2026-08-20", false, 422);
    linkWithOverrides(token, current, currentEntry, definition, "2026-08-21", false, 409, 1, 0, 0);
    linkWithOverrides(token, current, currentEntry, definition, "2026-08-21", false, 409, 0, 1, 0);
    linkWithOverrides(token, current, currentEntry, definition, "2026-08-21", false, 409, 0, 0, 1);

    JsonNode inactive =
        requestJson(
            post("/api/v1/recurring-bills/{id}/deactivate", definition.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":%d}".formatted(definition.path("version").asLong())),
            200);
    link(token, current, currentEntry, inactive, "2026-08-21", false, 422);

    JsonNode deleted = createDefinition(token, "Deleted", 1000, 21, "AUTOPAY", null, null, null);
    requestJson(
        delete(
                "/api/v1/recurring-bills/{id}?version={version}",
                deleted.path("id").asText(),
                deleted.path("version").asLong())
            .header("Authorization", bearer(token)),
        204);
    link(token, current, currentEntry, deleted, "2026-08-21", false, 404);
    assertThat(entryRow(entry.path("id").asText()).get("name")).isEqualTo("Rent");
  }

  @Test
  void updatesTheActivePaybackRepaymentWhenLinkingAPostedBill() throws Exception {
    String token = register("recurring-posted-payback@yuuka.local");
    JsonNode payback = createPayback(token, "Protected cash", 10000, 10000);
    JsonNode definition =
        createDefinition(token, "Final repayment", 6000, 21, "AUTOPAY", null, null, null);
    JsonNode paycheck = createPaycheck(token, "Repayment", 10000, "2026-08-15");
    JsonNode entry =
        addBill(
            token,
            paycheck,
            "Draft repayment",
            4000,
            "2026-08-18",
            "MANUAL",
            payback.path("id").asText());
    entry = changeStatus(token, entry, "POSTED");
    assertThat(getPayback(token, payback.path("id").asText()).path("remainingMinor").asLong())
        .isEqualTo(6000);
    paycheck = getPaycheck(token, paycheck.path("id").asText());

    JsonNode linked = link(token, paycheck, entry, definition, "2026-08-21", false, 200);
    JsonNode linkedEntry = linked.path("entries").get(0);

    assertThat(linkedEntry.path("id").asText()).isEqualTo(entry.path("id").asText());
    assertThat(linkedEntry.path("status").asText()).isEqualTo("POSTED");
    assertThat(linkedEntry.path("paybackId").asText()).isEqualTo(payback.path("id").asText());
    assertThat(getPayback(token, payback.path("id").asText()).path("remainingMinor").asLong())
        .isEqualTo(4000);
    assertThat(statusHistoryCount(entry.path("id").asText())).isEqualTo(1);
  }

  @Test
  void disclosesExistingAssignmentsAndAllowsExplicitDuplicates() throws Exception {
    String token = register("recurring-duplicate-link@yuuka.local");
    JsonNode definition =
        createDefinition(token, "Electric", 5000, 21, "AUTOPAY", null, null, null);
    JsonNode firstPaycheck = createPaycheck(token, "Utilities 1/2", 10000, "2026-08-01");
    JsonNode firstEntry = addBill(token, firstPaycheck, "Legacy one", 4000, null, "AUTOPAY");
    firstPaycheck = getPaycheck(token, firstPaycheck.path("id").asText());
    link(token, firstPaycheck, firstEntry, definition, "2026-08-21", false, 200);

    JsonNode secondPaycheck = createPaycheck(token, "Utilities 2/2", 10000, "2026-08-15");
    JsonNode secondEntry = addBill(token, secondPaycheck, "Legacy two", 4000, null, "AUTOPAY");
    secondPaycheck = getPaycheck(token, secondPaycheck.path("id").asText());
    MvcResult warning =
        linkResult(token, secondPaycheck, secondEntry, definition, "2026-08-21", false, 422);
    JsonNode error = objectMapper.readTree(warning.getResponse().getContentAsString());
    assertThat(error.path("code").asText()).isEqualTo("RECURRING_OCCURRENCE_ALREADY_ASSIGNED");
    assertThat(error.path("details").path("assignments").get(0).path("paycheckName").asText())
        .isEqualTo("Utilities 1/2");

    JsonNode duplicated =
        link(token, secondPaycheck, secondEntry, definition, "2026-08-21", true, 200);
    assertThat(duplicated.path("entries").get(0).path("sourceRecurringOccurrenceDate").asText())
        .isEqualTo("2026-08-21");
    mockMvc
        .perform(
            get("/api/v1/recurring-bills/timeline?from=2026-08-21&through=2026-08-21")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].importCount").value(2));
  }

  @Test
  void createsDefinitionFromBillAndRollsBackDefinitionWhenNormalizationFails() throws Exception {
    String token = register("recurring-create-from-entry@yuuka.local");
    String other = register("recurring-create-from-entry-other@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Create source", 20000, "2026-08-15");
    JsonNode entry = addBill(token, paycheck, "Old", 5000, null, "MANUAL");
    paycheck = getPaycheck(token, paycheck.path("id").asText());

    Map<String, Object> request = new LinkedHashMap<>();
    request.put("entryVersion", entry.path("version").asLong());
    request.put("paycheckVersion", paycheck.path("version").asLong());
    request.put("name", "Edited recurring");
    request.put("typicalAmountMinor", 7000);
    request.put("paymentMethod", "AUTOPAY");
    request.put("dueDay", 31);
    request.put("accountName", "Card");
    request.put("payee", "Vendor");
    request.put("notes", "Final definition");
    request.put("occurrenceDate", "2026-08-31");
    long definitionsBeforeConflict = definitionCount(token);
    request.put("entryVersion", entry.path("version").asLong() + 1);
    requestJson(
        post("/api/v1/entries/{id}/recurring-bill-definition", entry.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)),
        409);
    assertThat(definitionCount(token)).isEqualTo(definitionsBeforeConflict);
    request.put("entryVersion", entry.path("version").asLong());
    requestJson(
        post("/api/v1/entries/{id}/recurring-bill-definition", entry.path("id").asText())
            .header("Authorization", bearer(other))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)),
        404);
    assertThat(definitionCount(other)).isZero();

    JsonNode created =
        requestJson(
            post("/api/v1/entries/{id}/recurring-bill-definition", entry.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
            200);
    JsonNode createdEntry = created.path("entries").get(0);
    assertThat(createdEntry.path("id").asText()).isEqualTo(entry.path("id").asText());
    assertThat(createdEntry.path("name").asText()).isEqualTo("Edited recurring");
    assertThat(createdEntry.path("amountMinor").asLong()).isEqualTo(7000);
    assertThat(createdEntry.path("dueDate").asText()).isEqualTo("2026-08-31");
    UUID definitionId =
        UUID.fromString(createdEntry.path("sourceRecurringBillDefinitionId").asText());
    assertThat(
            jdbcTemplate.queryForObject(
                "select due_day from recurring_bill_definitions where id = ?",
                Integer.class,
                definitionId))
        .isEqualTo(31);
    assertThat(auditCount(entry.path("id").asText(), "RECURRING_BILL_CREATED_AND_LINKED"))
        .isEqualTo(1);

    JsonNode small = createPaycheck(token, "Rollback source", 1000, "2026-09-01");
    JsonNode smallEntry = addBill(token, small, "Small", 500, null, "AUTOPAY");
    small = getPaycheck(token, small.path("id").asText());
    long definitionsBefore = definitionCount(token);
    request.put("entryVersion", smallEntry.path("version").asLong());
    request.put("paycheckVersion", small.path("version").asLong());
    request.put("typicalAmountMinor", 2000);
    request.put("occurrenceDate", "2026-09-30");
    request.put("dueDay", 30);
    requestJson(
        post("/api/v1/entries/{id}/recurring-bill-definition", smallEntry.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)),
        422);
    assertThat(definitionCount(token)).isEqualTo(definitionsBefore);
    assertThat(entryRow(smallEntry.path("id").asText()).get("name")).isEqualTo("Small");
  }

  private JsonNode link(
      String token,
      JsonNode paycheck,
      JsonNode entry,
      JsonNode definition,
      String occurrenceDate,
      boolean confirmDuplicate,
      int status)
      throws Exception {
    MvcResult result =
        linkResult(token, paycheck, entry, definition, occurrenceDate, confirmDuplicate, status);
    String content = result.getResponse().getContentAsString();
    return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
  }

  private MvcResult linkResult(
      String token,
      JsonNode paycheck,
      JsonNode entry,
      JsonNode definition,
      String occurrenceDate,
      boolean confirmDuplicate,
      int status)
      throws Exception {
    return linkWithOverrides(
        token, paycheck, entry, definition, occurrenceDate, confirmDuplicate, status, 0, 0, 0);
  }

  private MvcResult linkWithOverrides(
      String token,
      JsonNode paycheck,
      JsonNode entry,
      JsonNode definition,
      String occurrenceDate,
      boolean confirmDuplicate,
      int status,
      long entryVersionOffset,
      long paycheckVersionOffset,
      long definitionVersionOffset)
      throws Exception {
    Map<String, Object> body =
        Map.of(
            "entryVersion",
            entry.path("version").asLong() + entryVersionOffset,
            "paycheckVersion",
            paycheck.path("version").asLong() + paycheckVersionOffset,
            "definitionId",
            definition.path("id").asText(),
            "definitionVersion",
            definition.path("version").asLong() + definitionVersionOffset,
            "occurrenceDate",
            occurrenceDate,
            "confirmDuplicateOccurrence",
            confirmDuplicate);
    return mockMvc
        .perform(
            put("/api/v1/entries/{id}/recurring-bill-link", entry.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().is(status))
        .andReturn();
  }

  private JsonNode unlink(String token, JsonNode paycheck, JsonNode entry, int status)
      throws Exception {
    return requestJson(
        delete(
                "/api/v1/entries/{id}/recurring-bill-link?entryVersion={entryVersion}&paycheckVersion={paycheckVersion}",
                entry.path("id").asText(),
                entry.path("version").asLong(),
                paycheck.path("version").asLong())
            .header("Authorization", bearer(token)),
        status);
  }

  private JsonNode createDefinition(
      String token,
      String name,
      long amount,
      int dueDay,
      String paymentMethod,
      String account,
      String payee,
      String notes)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("typicalAmountMinor", amount);
    body.put("paymentMethod", paymentMethod);
    body.put("dueDay", dueDay);
    body.put("accountName", account);
    body.put("payee", payee);
    body.put("notes", notes);
    return requestJson(
        post("/api/v1/recurring-bills")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)),
        201);
  }

  private JsonNode createPaycheck(String token, String name, long amount, String incomeDate)
      throws Exception {
    return requestJson(
        post("/api/v1/paychecks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"%s","amountMinor":%d,"incomeDate":"%s"}
                """
                    .formatted(name, amount, incomeDate)),
        201);
  }

  private JsonNode addBill(
      String token,
      JsonNode paycheck,
      String name,
      long amount,
      String dueDate,
      String paymentMethod)
      throws Exception {
    return addBill(token, paycheck, name, amount, dueDate, paymentMethod, null);
  }

  private JsonNode addBill(
      String token,
      JsonNode paycheck,
      String name,
      long amount,
      String dueDate,
      String paymentMethod,
      String paybackId)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("entryType", "BILL");
    body.put("name", name);
    body.put("amountMinor", amount);
    body.put("paymentMethod", paymentMethod);
    body.put("dueDate", dueDate);
    body.put("accountName", "Old account");
    body.put("payee", "Old payee");
    body.put("notes", "Old notes");
    body.put("paybackId", paybackId);
    return requestJson(
        post("/api/v1/paychecks/{id}/entries", paycheck.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)),
        201);
  }

  private JsonNode createPayback(
      String token, String name, long originalAmount, long openingRemaining) throws Exception {
    return requestJson(
        post("/api/v1/paybacks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "name":"%s",
                  "originalAmountMinor":%d,
                  "openingRemainingAmountMinor":%d,
                  "borrowedDate":"2026-08-01"
                }
                """
                    .formatted(name, originalAmount, openingRemaining)),
        201);
  }

  private JsonNode getPayback(String token, String paybackId) throws Exception {
    return requestJson(
        get("/api/v1/paybacks/{id}", paybackId).header("Authorization", bearer(token)), 200);
  }

  private JsonNode changeStatus(String token, JsonNode entry, String status) throws Exception {
    return requestJson(
        post("/api/v1/entries/{id}/status", entry.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"%s","effectiveAt":"2026-08-19T12:00:00Z","version":%d}
                """
                    .formatted(status, entry.path("version").asLong())),
        200);
  }

  private JsonNode getPaycheck(String token, String paycheckId) throws Exception {
    return requestJson(
        get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", bearer(token)), 200);
  }

  private JsonNode requestJson(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int status)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(status)).andReturn();
    String content = result.getResponse().getContentAsString();
    return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
  }

  private long statusHistoryCount(String entryId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from entry_status_events where entry_id = ?",
        Long.class,
        UUID.fromString(entryId));
  }

  private long auditCount(String entryId, String action) {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_events where entity_id = ? and action = ?",
        Long.class,
        UUID.fromString(entryId),
        action);
  }

  private long definitionCount(String token) throws Exception {
    return requestJson(
            get("/api/v1/recurring-bills?status=ALL").header("Authorization", bearer(token)), 200)
        .path("items")
        .size();
  }

  private Map<String, Object> entryRow(String entryId) {
    return jdbcTemplate.queryForMap(
        "select name, amount_minor, source_recurring_bill_definition_id from paycheck_entries where id = ?",
        UUID.fromString(entryId));
  }

  private String register(String email) throws Exception {
    return requestJson(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"Password12345","displayName":"Test"}
                    """
                        .formatted(email)),
            201)
        .path("accessToken")
        .asText();
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
