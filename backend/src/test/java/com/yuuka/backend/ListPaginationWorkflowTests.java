package com.yuuka.backend;

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
class ListPaginationWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void activePaychecksPageEligibleRowsWithStableOrderingAndOwnerIsolation() throws Exception {
    String token = register("list-active-owner@yuuka.local");
    String otherToken = register("list-active-other@yuuka.local");

    JsonNode older = createPaycheck(token, "Older Active", 1000, "2026-07-01", null);
    JsonNode newer = createPaycheck(token, "Newer Active", 1000, "2026-07-03", null);
    JsonNode completed = createPaycheck(token, "Legacy Complete", 1000, "2026-07-04", null);
    JsonNode completedEntry =
        addEntry(token, completed.path("id").asText(), "BILL", "Complete Bill", 1000, null);
    changeStatus(
        token, completedEntry.path("id").asText(), completedEntry.path("version").asLong());
    jdbcTemplate.update(
        "update paychecks set state = 'ACTIVE', closed_at = null, reopened_at = null where id = ?",
        UUID.fromString(completed.path("id").asText()));
    createPaycheck(otherToken, "Other Active", 1000, "2026-07-05", null);

    mockMvc
        .perform(
            get("/api/v1/paychecks/active")
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(newer.path("id").asText()))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(1))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/paychecks/active")
                .param("page", "1")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(older.path("id").asText()))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/paychecks/active")
                .param("page", "99")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void historyPaychecksPageSearchAndDateFiltersWithOwnerIsolation() throws Exception {
    String token = register("list-history-owner@yuuka.local");
    String otherToken = register("list-history-other@yuuka.local");

    JsonNode first = closedPaycheck(token, "Utility Paycheck", 1000, "2026-07-01", "Employer");
    JsonNode second = closedPaycheck(token, "Groceries", 1000, "2026-07-10", "Utility Refund");
    closedPaycheck(token, "Utility Old", 1000, "2026-06-15", null);
    closedPaycheck(otherToken, "Utility Other", 1000, "2026-07-02", null);

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "utility")
                .param("from", "2026-07-01")
                .param("to", "2026-07-31")
                .param("oldestFirst", "true")
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(first.path("id").asText()))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "utility")
                .param("from", "2026-07-01")
                .param("to", "2026-07-31")
                .param("oldestFirst", "true")
                .param("page", "1")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(second.path("id").asText()))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void paybackRepaymentsPageByAppliedTimeAndStayOwnerScoped() throws Exception {
    String token = register("list-repayments-owner@yuuka.local");
    String otherToken = register("list-repayments-other@yuuka.local");
    JsonNode payback = createPayback(token, "Owner Payback");
    JsonNode otherPayback = createPayback(otherToken, "Other Payback");
    JsonNode paycheck = createPaycheck(token, "Repayment Check", 10000, "2026-07-17", null);
    JsonNode oldest = addEntry(token, paycheck.path("id").asText(), "BILL", "Oldest", 1000, null);
    JsonNode newest = addEntry(token, paycheck.path("id").asText(), "BILL", "Newest", 1000, null);
    JsonNode middle = addEntry(token, paycheck.path("id").asText(), "BILL", "Middle", 1000, null);
    JsonNode otherPaycheck =
        createPaycheck(otherToken, "Other Repayment Check", 10000, "2026-07-17", null);
    JsonNode otherEntry =
        addEntry(otherToken, otherPaycheck.path("id").asText(), "BILL", "Other", 1000, null);

    UUID ownerId = ownerIdForPayback(payback.path("id").asText());
    UUID otherOwnerId = ownerIdForPayback(otherPayback.path("id").asText());
    insertRepayment(
        ownerId, payback.path("id").asText(), oldest.path("id").asText(), "2026-07-17T10:00:00Z");
    insertRepayment(
        ownerId, payback.path("id").asText(), newest.path("id").asText(), "2026-07-17T12:00:00Z");
    insertRepayment(
        ownerId, payback.path("id").asText(), middle.path("id").asText(), "2026-07-17T11:00:00Z");
    insertRepayment(
        otherOwnerId,
        otherPayback.path("id").asText(),
        otherEntry.path("id").asText(),
        "2026-07-17T13:00:00Z");

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}/repayments", payback.path("id").asText())
                .param("page", "0")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].entryName").value("Newest"))
        .andExpect(jsonPath("$.items[1].entryName").value("Middle"))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}/repayments", payback.path("id").asText())
                .param("page", "1")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].entryName").value("Oldest"))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}/repayments", payback.path("id").asText())
                .header("Authorization", bearer(otherToken)))
        .andExpect(status().isNotFound());
  }

  @Test
  void bucketTransactionsPageByEffectiveDateAndStayOwnerScoped() throws Exception {
    String token = register("list-buckets-owner@yuuka.local");
    String otherToken = register("list-buckets-other@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Bucket Check", 10000, "2026-07-17", null);
    JsonNode bucket =
        addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", "Groceries", 5000, null);
    JsonNode otherPaycheck =
        createPaycheck(otherToken, "Other Bucket Check", 10000, "2026-07-17", null);
    JsonNode otherBucket =
        addEntry(
            otherToken,
            otherPaycheck.path("id").asText(),
            "SPENDING_BUCKET",
            "Other Groceries",
            5000,
            null);
    addBucketTransaction(token, bucket.path("id").asText(), 1000, "2026-07-10", "Oldest");
    addBucketTransaction(token, bucket.path("id").asText(), 1000, "2026-07-12", "Newest");
    addBucketTransaction(token, bucket.path("id").asText(), 1000, "2026-07-11", "Middle");
    addBucketTransaction(otherToken, otherBucket.path("id").asText(), 1000, "2026-07-13", "Other");

    mockMvc
        .perform(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .param("page", "0")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].description").value("Newest"))
        .andExpect(jsonPath("$.items[1].description").value("Middle"))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .param("page", "1")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].description").value("Oldest"))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .header("Authorization", bearer(otherToken)))
        .andExpect(status().isNotFound());
  }

  private JsonNode closedPaycheck(
      String token, String name, long amountMinor, String incomeDate, String source)
      throws Exception {
    JsonNode paycheck = createPaycheck(token, name, amountMinor, incomeDate, source);
    JsonNode entry =
        addEntry(token, paycheck.path("id").asText(), "BILL", name + " Bill", amountMinor, null);
    changeStatus(token, entry.path("id").asText(), entry.path("version").asLong());
    return paycheck;
  }

  private String register(String email) throws Exception {
    JsonNode result =
        json(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"Password12345","displayName":"Test"}
                    """
                        .formatted(email)),
            201);
    return result.path("accessToken").asText();
  }

  private JsonNode createPaycheck(
      String token, String name, long amountMinor, String incomeDate, String source)
      throws Exception {
    String sourceJson = source == null ? "null" : "\"%s\"".formatted(source);
    return json(
        post("/api/v1/paychecks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"%s","source":%s,"amountMinor":%d,"incomeDate":"%s"}
                """
                    .formatted(name, sourceJson, amountMinor, incomeDate)),
        201);
  }

  private JsonNode addEntry(
      String token,
      String paycheckId,
      String entryType,
      String name,
      long amountMinor,
      String paybackId)
      throws Exception {
    String paybackJson = paybackId == null ? "null" : "\"%s\"".formatted(paybackId);
    return json(
        post("/api/v1/paychecks/{id}/entries", paycheckId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"entryType":"%s","name":"%s","amountMinor":%d,"paybackId":%s}
                """
                    .formatted(entryType, name, amountMinor, paybackJson)),
        201);
  }

  private JsonNode createPayback(String token, String name) throws Exception {
    return json(
        post("/api/v1/paybacks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "name":"%s",
                  "originalAmountMinor":10000,
                  "openingRemainingAmountMinor":10000,
                  "borrowedDate":"2026-07-01"
                }
                """
                    .formatted(name)),
        201);
  }

  private JsonNode addBucketTransaction(
      String token, String entryId, long amountMinor, String effectiveDate, String description)
      throws Exception {
    return json(
        post("/api/v1/entries/{id}/bucket-transactions", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"amountMinor":%d,"effectiveDate":"%s","description":"%s"}
                """
                    .formatted(amountMinor, effectiveDate, description)),
        201);
  }

  private JsonNode changeStatus(String token, String entryId, long version) throws Exception {
    return json(
        post("/api/v1/entries/{id}/status", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"POSTED","effectiveAt":"2026-07-17T12:00:00Z","version":%d}
                """
                    .formatted(version)),
        200);
  }

  private void insertRepayment(UUID ownerId, String paybackId, String entryId, String appliedAt) {
    jdbcTemplate.update(
        """
        insert into payback_repayments
          (id, owner_id, payback_id, entry_id, amount_minor, applied_at, created_at, updated_at, version)
        values (?, ?, ?, ?, 1000, ?, now(), now(), 0)
        """,
        UUID.randomUUID(),
        ownerId,
        UUID.fromString(paybackId),
        UUID.fromString(entryId),
        Instant.parse(appliedAt));
  }

  private UUID ownerIdForPayback(String paybackId) {
    return jdbcTemplate.queryForObject(
        "select owner_id from paybacks where id = ?", UUID.class, UUID.fromString(paybackId));
  }

  private JsonNode json(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
