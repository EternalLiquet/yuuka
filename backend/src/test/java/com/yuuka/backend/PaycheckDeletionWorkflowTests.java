package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class PaycheckDeletionWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void deletesActivePaycheckAndPreservesImmutableHistory() throws Exception {
    String token = register("paycheck-delete@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Accidental duplicate", 5000);
    JsonNode bill = addEntry(token, paycheck, "BILL", "Rent copy", 3000, null, null);
    JsonNode bucket = addEntry(token, paycheck, "SPENDING_BUCKET", "Food", 2000, null, null);
    addBucketPurchase(token, bucket, 1250);

    deletePaycheck(token, paycheck(token, paycheck.path("id").asText()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/paychecks/{id}", paycheck.path("id").asText()).with(auth(token)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/paychecks/active").with(auth(token)))
        .andExpect(jsonPath("$.items").isEmpty());
    mockMvc
        .perform(get("/api/v1/paychecks/history").with(auth(token)))
        .andExpect(jsonPath("$.items").isEmpty());
    mockMvc
        .perform(get("/api/v1/search/entries?query=Rent").with(auth(token)))
        .andExpect(jsonPath("$.items").isEmpty());

    UUID paycheckId = UUID.fromString(paycheck.path("id").asText());
    assertThat(value("select deleted_at from paychecks where id = ?", Instant.class, paycheckId))
        .isNotNull();
    assertThat(
            value(
                "select count(*) from paycheck_entries where paycheck_id = ? and deleted_at is null",
                Long.class,
                paycheckId))
        .isZero();
    assertThat(
            value(
                "select count(*) from entry_status_events event join paycheck_entries entry on entry.id = event.entry_id where entry.paycheck_id = ?",
                Long.class,
                paycheckId))
        .isEqualTo(2);
    assertThat(
            value(
                "select count(*) from bucket_transactions where entry_id = ?",
                Long.class,
                UUID.fromString(bucket.path("id").asText())))
        .isEqualTo(1);
    assertThat(
            value(
                "select count(*) from audit_events where entity_type = 'PAYCHECK' and entity_id = ? and action = 'DELETED'",
                Long.class,
                paycheckId))
        .isEqualTo(1);
  }

  @Test
  void enforcesOwnerVersionAndActiveLifecycle() throws Exception {
    String owner = register("paycheck-delete-owner@yuuka.local");
    String other = register("paycheck-delete-other@yuuka.local");
    JsonNode paycheck = createPaycheck(owner, "Protected", 1000);

    deletePaycheck(other, paycheck).andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/delete", paycheck.path("id").asText())
                .with(auth(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":99}"))
        .andExpect(status().isConflict());

    JsonNode entry = addEntry(owner, paycheck, "BILL", "Complete", 1000, null, null);
    postEntry(owner, entry);
    JsonNode closed = paycheck(owner, paycheck.path("id").asText());
    deletePaycheck(owner, closed).andExpect(status().isUnprocessableEntity());
    assertThat(
            value(
                "select deleted_at from paychecks where id = ?",
                Instant.class,
                UUID.fromString(paycheck.path("id").asText())))
        .isNull();
  }

  @Test
  void reversesPostedPaybackAndPlannedSavingsEffectsExactlyOnce() throws Exception {
    String token = register("paycheck-delete-effects@yuuka.local");
    JsonNode payback = createPayback(token);
    JsonNode fund = createFund(token);
    JsonNode paycheck = createPaycheck(token, "Linked accidental copy", 3000);
    JsonNode paybackEntry =
        addEntry(token, paycheck, "BILL", "Repayment", 1000, payback.path("id").asText(), null);
    JsonNode fundEntry =
        addEntry(
            token,
            paycheck,
            "SINKING_FUND",
            "Emergency savings",
            1000,
            null,
            fund.path("id").asText());
    postEntry(token, paybackEntry);
    postEntry(token, fundEntry);

    JsonNode active = paycheck(token, paycheck.path("id").asText());
    deletePaycheck(token, active).andExpect(status().isNoContent());

    UUID paybackEntryId = UUID.fromString(paybackEntry.path("id").asText());
    UUID fundEntryId = UUID.fromString(fundEntry.path("id").asText());
    assertThat(
            value(
                "select count(*) from payback_repayments where entry_id = ? and reversed_at is not null",
                Long.class,
                paybackEntryId))
        .isEqualTo(1);
    assertThat(
            value(
                "select count(*) from sinking_fund_transactions where entry_id = ? and reversed_at is not null",
                Long.class,
                fundEntryId))
        .isEqualTo(1);
  }

  @Test
  void leavesAlreadyReversedLinkedEffectsReversed() throws Exception {
    String token = register("paycheck-delete-reversed-effects@yuuka.local");
    JsonNode payback = createPayback(token);
    JsonNode fund = createFund(token);
    JsonNode paycheck = createPaycheck(token, "Already reversed", 3000);
    JsonNode paybackEntry =
        addEntry(token, paycheck, "BILL", "Repayment", 1000, payback.path("id").asText(), null);
    JsonNode fundEntry =
        addEntry(
            token,
            paycheck,
            "SINKING_FUND",
            "Emergency savings",
            1000,
            null,
            fund.path("id").asText());
    JsonNode postedPayback = changeEntryStatus(token, paybackEntry, "POSTED");
    JsonNode postedFund = changeEntryStatus(token, fundEntry, "POSTED");
    changeEntryStatus(token, postedPayback, "NOT_PAID");
    changeEntryStatus(token, postedFund, "NOT_PAID");

    deletePaycheck(token, paycheck(token, paycheck.path("id").asText()))
        .andExpect(status().isNoContent());

    for (JsonNode entry : new JsonNode[] {paybackEntry, fundEntry}) {
      UUID entryId = UUID.fromString(entry.path("id").asText());
      String table = entry == paybackEntry ? "payback_repayments" : "sinking_fund_transactions";
      assertThat(
              value(
                  "select count(*) from "
                      + table
                      + " where entry_id = ? and reversed_at is not null",
                  Long.class,
                  entryId))
          .isEqualTo(1);
      assertThat(
              value(
                  "select count(*) from " + table + " where entry_id = ? and reversed_at is null",
                  Long.class,
                  entryId))
          .isZero();
    }
  }

  @Test
  void removesRecurringCoverageAndSpendingBucketPerformance() throws Exception {
    String token = register("paycheck-delete-derived-views@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Derived views", 3000);
    JsonNode definition = createRecurringBill(token);
    importRecurringBill(token, paycheck, definition);
    JsonNode bucket = addEntry(token, paycheck, "SPENDING_BUCKET", "Food", 2000, null, null);
    addBucketPurchase(token, bucket, 500);

    recurringTimeline(token).andExpect(jsonPath("$.items[0].importCount").value(1));
    rollingPerformance(token)
        .andExpect(jsonPath("$.paycheckCount").value(1))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(2000))
        .andExpect(jsonPath("$.summary.spentMinor").value(500));

    deletePaycheck(token, paycheck(token, paycheck.path("id").asText()))
        .andExpect(status().isNoContent());

    recurringTimeline(token)
        .andExpect(jsonPath("$.items[0].importCount").value(0))
        .andExpect(jsonPath("$.items[0].definitionId").value(definition.path("id").asText()));
    rollingPerformance(token)
        .andExpect(jsonPath("$.paycheckCount").value(0))
        .andExpect(jsonPath("$.summary").value((Object) null));
  }

  @Test
  void preservesExpenseListSettlementProvenance() throws Exception {
    String token = register("paycheck-delete-expense-list@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Expense settlement", 5000);
    JsonNode ledger = createExpenseLedger(token);
    addExpenseItem(token, ledger, 2500);
    JsonNode finalized = finalizeExpenseLedger(token, ledger);
    JsonNode settlement = settleExpenseLedger(token, finalized, paycheck);
    String targetEntryId = settlement.path("billEntry").path("id").asText();

    deletePaycheck(token, paycheck(token, paycheck.path("id").asText()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/expense-ledgers/{id}", ledger.path("id").asText()).with(auth(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SETTLED"))
        .andExpect(jsonPath("$.settlement.targetId").value(targetEntryId))
        .andExpect(jsonPath("$.settlement.targetPaycheckId").value(paycheck.path("id").asText()));
    assertThat(
            value(
                "select count(*) from expense_ledger_settlements where ledger_id = ?",
                Long.class,
                UUID.fromString(ledger.path("id").asText())))
        .isEqualTo(1);
  }

  @Test
  void rollsBackEveryDeletionEffectWhenDependentCleanupFails() throws Exception {
    String token = register("paycheck-delete-rollback@yuuka.local");
    JsonNode payback = createPayback(token);
    JsonNode fund = createFund(token);
    JsonNode paycheck = createPaycheck(token, "Rollback accidental copy", 3000);
    JsonNode paybackEntry =
        addEntry(token, paycheck, "BILL", "Repayment", 1000, payback.path("id").asText(), null);
    JsonNode fundEntry =
        addEntry(
            token,
            paycheck,
            "SINKING_FUND",
            "Emergency savings",
            1000,
            null,
            fund.path("id").asText());
    postEntry(token, paybackEntry);
    postEntry(token, fundEntry);
    JsonNode currentFund =
        json(get("/api/v1/sinking-funds/{id}", fund.path("id").asText()).with(auth(token)), 200);
    withdrawFund(token, currentFund, 1000);

    JsonNode active = paycheck(token, paycheck.path("id").asText());
    deletePaycheck(token, active).andExpect(status().isUnprocessableEntity());

    UUID paycheckId = UUID.fromString(paycheck.path("id").asText());
    assertThat(value("select deleted_at from paychecks where id = ?", Instant.class, paycheckId))
        .isNull();
    assertThat(
            value(
                "select count(*) from paycheck_entries where paycheck_id = ? and deleted_at is null",
                Long.class,
                paycheckId))
        .isEqualTo(2);
    assertThat(
            value(
                "select count(*) from payback_repayments where entry_id = ? and reversed_at is null",
                Long.class,
                UUID.fromString(paybackEntry.path("id").asText())))
        .isEqualTo(1);
  }

  private JsonNode createPaycheck(String token, String name, long amountMinor) throws Exception {
    return json(
        post("/api/v1/paychecks")
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"%s","amountMinor":%d,"incomeDate":"2026-09-24"}
                """
                    .formatted(name, amountMinor)),
        201);
  }

  private JsonNode addEntry(
      String token,
      JsonNode paycheck,
      String type,
      String name,
      long amount,
      String paybackId,
      String fundId)
      throws Exception {
    return json(
        post("/api/v1/paychecks/{id}/entries", paycheck.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    entryPayload(type, name, amount, paybackId, fundId))),
        201);
  }

  private void postEntry(String token, JsonNode entry) throws Exception {
    changeEntryStatus(token, entry, "POSTED");
  }

  private JsonNode changeEntryStatus(String token, JsonNode entry, String status) throws Exception {
    return json(
        post("/api/v1/entries/{id}/status", entry.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"%s","effectiveAt":"2026-09-24T12:00:00Z","version":%d}
                """
                    .formatted(status, entry.path("version").asLong())),
        200);
  }

  private JsonNode createRecurringBill(String token) throws Exception {
    return json(
        post("/api/v1/recurring-bills")
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"Internet","amountMode":"FIXED","typicalAmountMinor":1000,"paymentMethod":"AUTOPAY","dueDay":24}
                """),
        201);
  }

  private void importRecurringBill(String token, JsonNode paycheck, JsonNode definition)
      throws Exception {
    json(
        post("/api/v1/paychecks/{id}/recurring-bill-imports", paycheck.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"paycheckVersion":%d,"items":[{"definitionId":"%s","definitionVersion":%d,"occurrenceDate":"2026-09-24","amountMinor":1000,"updateTypicalAmount":false}]}
                """
                    .formatted(
                        paycheck.path("version").asLong(),
                        definition.path("id").asText(),
                        definition.path("version").asLong())),
        200);
  }

  private org.springframework.test.web.servlet.ResultActions recurringTimeline(String token)
      throws Exception {
    return mockMvc.perform(
        get("/api/v1/recurring-bills/timeline?from=2026-09-24&through=2026-09-24")
            .with(auth(token)));
  }

  private org.springframework.test.web.servlet.ResultActions rollingPerformance(String token)
      throws Exception {
    return mockMvc.perform(
        get("/api/v1/spending-buckets/performance/rolling")
            .queryParam("days", "90")
            .queryParam("asOfDate", "2026-09-24")
            .with(auth(token)));
  }

  private JsonNode createExpenseLedger(String token) throws Exception {
    return json(
        post("/api/v1/expense-ledgers")
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Settlement source\"}"),
        201);
  }

  private void addExpenseItem(String token, JsonNode ledger, long amount) throws Exception {
    json(
        post("/api/v1/expense-ledgers/{id}/items", ledger.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"Groceries","amountMinor":%d,"expenseDate":"2026-09-24"}
                """
                    .formatted(amount)),
        201);
  }

  private JsonNode finalizeExpenseLedger(String token, JsonNode ledger) throws Exception {
    JsonNode current =
        json(
            get("/api/v1/expense-ledgers/{id}", ledger.path("id").asText()).with(auth(token)), 200);
    return json(
        post("/api/v1/expense-ledgers/{id}/finalize", ledger.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + current.path("version").asLong() + "}"),
        200);
  }

  private JsonNode settleExpenseLedger(String token, JsonNode ledger, JsonNode paycheck)
      throws Exception {
    return json(
        post("/api/v1/expense-ledgers/{id}/settle/bill", ledger.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"paycheckId":"%s","ledgerVersion":%d}
                """
                    .formatted(paycheck.path("id").asText(), ledger.path("version").asLong())),
        200);
  }

  private JsonNode createPayback(String token) throws Exception {
    return json(
        post("/api/v1/paybacks")
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"Family","originalAmountMinor":5000,"openingRemainingAmountMinor":5000,"borrowedDate":"2026-09-01"}
                """),
        201);
  }

  private JsonNode createFund(String token) throws Exception {
    return json(
        post("/api/v1/sinking-funds")
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"Emergency","targetMinor":10000,"targetDate":"2026-12-31"}
                """),
        201);
  }

  private void addBucketPurchase(String token, JsonNode entry, long amount) throws Exception {
    json(
        post("/api/v1/entries/{id}/bucket-transactions", entry.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"amountMinor":%d,"description":"Lunch","effectiveDate":"2026-09-24"}
                """
                    .formatted(amount)),
        201);
  }

  private void withdrawFund(String token, JsonNode fund, long amount) throws Exception {
    json(
        post("/api/v1/sinking-funds/{id}/withdrawals", fund.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"amountMinor":%d,"effectiveDate":"2026-09-24","reason":"Spent","version":%d}
                """
                    .formatted(amount, fund.path("version").asLong())),
        201);
  }

  private org.springframework.test.web.servlet.ResultActions deletePaycheck(
      String token, JsonNode paycheck) throws Exception {
    return mockMvc.perform(
        post("/api/v1/paychecks/{id}/delete", paycheck.path("id").asText())
            .with(auth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + paycheck.path("version").asLong() + "}"));
  }

  private JsonNode paycheck(String token, String id) throws Exception {
    return json(get("/api/v1/paychecks/{id}", id).with(auth(token)), 200);
  }

  private String register(String email) throws Exception {
    return json(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"Password12345","displayName":"Delete Test"}
                    """
                        .formatted(email)),
            201)
        .path("accessToken")
        .asText();
  }

  private org.springframework.test.web.servlet.request.RequestPostProcessor auth(String token) {
    return request -> {
      request.addHeader("Authorization", "Bearer " + token);
      return request;
    };
  }

  private java.util.Map<String, Object> entryPayload(
      String type, String name, long amount, String paybackId, String fundId) {
    java.util.Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("entryType", type);
    payload.put("name", name);
    payload.put("amountMinor", amount);
    if (paybackId != null) payload.put("paybackId", paybackId);
    if (fundId != null) payload.put("sinkingFundId", fundId);
    return payload;
  }

  private JsonNode json(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int status)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(status)).andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private <T> T value(String sql, Class<T> type, Object... args) {
    return jdbcTemplate.queryForObject(sql, type, args);
  }
}
