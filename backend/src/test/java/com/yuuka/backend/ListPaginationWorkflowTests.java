package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    mockMvc
        .perform(
            get("/api/v1/paychecks/active")
                .param("page", "-5")
                .param("size", "0")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(newer.path("id").asText()))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(1));

    mockMvc
        .perform(
            get("/api/v1/paychecks/active")
                .param("page", "0")
                .param("size", "101")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.size").value(100))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void activePaychecksUseStableUuidTieBreakerForEqualSortValues() throws Exception {
    String token = register("list-active-ties@yuuka.local");
    JsonNode first = createPaycheck(token, "Tie One", 1000, "2026-07-17", null);
    JsonNode second = createPaycheck(token, "Tie Two", 1000, "2026-07-17", null);
    JsonNode third = createPaycheck(token, "Tie Three", 1000, "2026-07-17", null);
    Timestamp tiedTimestamp = Timestamp.from(Instant.parse("2026-07-17T12:00:00Z"));
    jdbcTemplate.update(
        "update paychecks set income_date = '2026-07-17', updated_at = ? where id in (?, ?, ?)",
        tiedTimestamp,
        UUID.fromString(first.path("id").asText()),
        UUID.fromString(second.path("id").asText()),
        UUID.fromString(third.path("id").asText()));

    JsonNode pageZero =
        json(
            get("/api/v1/paychecks/active")
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);
    JsonNode pageOne =
        json(
            get("/api/v1/paychecks/active")
                .param("page", "1")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);
    JsonNode pageTwo =
        json(
            get("/api/v1/paychecks/active")
                .param("page", "2")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);
    JsonNode pageZeroAgain =
        json(
            get("/api/v1/paychecks/active")
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);

    List<String> returnedIds = new ArrayList<>();
    returnedIds.add(pageZero.path("items").get(0).path("id").asText());
    returnedIds.add(pageOne.path("items").get(0).path("id").asText());
    returnedIds.add(pageTwo.path("items").get(0).path("id").asText());
    assertThat(returnedIds)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrder(
            first.path("id").asText(), second.path("id").asText(), third.path("id").asText());
    assertThat(pageZeroAgain.path("items").get(0).path("id").asText())
        .isEqualTo(pageZero.path("items").get(0).path("id").asText());
  }

  @Test
  void historyPaychecksPageSearchAndDateFiltersWithOwnerIsolation() throws Exception {
    String token = register("list-history-owner@yuuka.local");
    String otherToken = register("list-history-other@yuuka.local");

    JsonNode percent = closedPaycheck(token, "Utility % Paycheck", 1000, "2026-07-01", "Employer");
    JsonNode underscore = closedPaycheck(token, "Groceries", 1000, "2026-07-10", "Utility_Refund");
    JsonNode backslash = closedPaycheck(token, "Gas", 1000, "2026-07-12", "Utility\\Refund");
    closedPaycheck(token, "Utility Old", 1000, "2026-06-15", null);
    closedPaycheck(otherToken, "Utility Other", 1000, "2026-07-02", null);

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "UtIlItY")
                .param("page", "0")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(backslash.path("id").asText()))
        .andExpect(jsonPath("$.items[1].id").value(underscore.path("id").asText()))
        .andExpect(jsonPath("$.totalItems").value(4))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "utility")
                .param("from", "2026-07-01")
                .param("page", "0")
                .param("size", "10")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "utility")
                .param("to", "2026-07-01")
                .param("page", "0")
                .param("size", "10")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.hasNext").value(false));

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
        .andExpect(jsonPath("$.items[0].id").value(percent.path("id").asText()))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(3))
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
        .andExpect(jsonPath("$.items[0].id").value(underscore.path("id").asText()))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "utility")
                .param("from", "2026-07-01")
                .param("to", "2026-07-31")
                .param("oldestFirst", "true")
                .param("page", "2")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(backslash.path("id").asText()))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "utility")
                .param("from", "2026-07-01")
                .param("to", "2026-07-31")
                .param("oldestFirst", "true")
                .param("page", "99")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.totalPages").value(3))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "%")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(percent.path("id").asText()))
        .andExpect(jsonPath("$.totalItems").value(1));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "_")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(underscore.path("id").asText()))
        .andExpect(jsonPath("$.totalItems").value(1));

    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "\\")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(backslash.path("id").asText()))
        .andExpect(jsonPath("$.totalItems").value(1));
  }

  @Test
  void paybackRepaymentsPageByAppliedTimeAndStayOwnerScoped() throws Exception {
    String token = register("list-repayments-owner@yuuka.local");
    String otherToken = register("list-repayments-other@yuuka.local");
    JsonNode payback = createPayback(token, "Owner Payback");
    JsonNode otherPayback = createPayback(otherToken, "Other Payback");
    JsonNode paycheck = createPaycheck(token, "Repayment Check", 10000, "2026-07-17", null);
    JsonNode oldest = addEntry(token, paycheck.path("id").asText(), "BILL", "Oldest", 1000, null);
    JsonNode newestLow =
        addEntry(token, paycheck.path("id").asText(), "BILL", "Newest Low", 1000, null);
    JsonNode newestHigh =
        addEntry(token, paycheck.path("id").asText(), "BILL", "Newest High", 1000, null);
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
        UUID.fromString("11111111-1111-4111-8111-000000000001"),
        ownerId,
        payback.path("id").asText(),
        newestLow.path("id").asText(),
        "2026-07-17T12:00:00Z");
    insertRepayment(
        UUID.fromString("11111111-1111-4111-8111-000000000002"),
        ownerId,
        payback.path("id").asText(),
        newestHigh.path("id").asText(),
        "2026-07-17T12:00:00Z");
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
        .andExpect(jsonPath("$.items[0].entryName").value("Newest High"))
        .andExpect(jsonPath("$.items[1].entryName").value("Newest Low"))
        .andExpect(jsonPath("$.totalItems").value(4))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}/repayments", payback.path("id").asText())
                .param("page", "1")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].entryName").value("Middle"))
        .andExpect(jsonPath("$.items[1].entryName").value("Oldest"))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}/repayments", payback.path("id").asText())
                .param("page", "99")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.totalItems").value(4))
        .andExpect(jsonPath("$.totalPages").value(2))
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

  @Test
  void bucketTransactionsUseStableUuidTieBreakerForEqualSortValues() throws Exception {
    String token = register("list-buckets-ties@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Bucket Tie Check", 10000, "2026-07-17", null);
    JsonNode bucket =
        addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", "Groceries", 5000, null);
    JsonNode first =
        addBucketTransaction(token, bucket.path("id").asText(), 1000, "2026-07-12", "Tie One");
    JsonNode second =
        addBucketTransaction(token, bucket.path("id").asText(), 1000, "2026-07-12", "Tie Two");
    JsonNode third =
        addBucketTransaction(token, bucket.path("id").asText(), 1000, "2026-07-12", "Tie Three");
    Timestamp tiedTimestamp = Timestamp.from(Instant.parse("2026-07-17T12:00:00Z"));
    jdbcTemplate.update(
        """
        update bucket_transactions
        set effective_date = '2026-07-12', created_at = ?
        where id in (?, ?, ?)
        """,
        tiedTimestamp,
        UUID.fromString(first.path("id").asText()),
        UUID.fromString(second.path("id").asText()),
        UUID.fromString(third.path("id").asText()));

    JsonNode pageZero =
        json(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);
    JsonNode pageOne =
        json(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .param("page", "1")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);
    JsonNode pageTwo =
        json(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .param("page", "2")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);
    JsonNode pageZeroAgain =
        json(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", bearer(token)),
            200);

    List<String> returnedIds = new ArrayList<>();
    returnedIds.add(pageZero.path("items").get(0).path("id").asText());
    returnedIds.add(pageOne.path("items").get(0).path("id").asText());
    returnedIds.add(pageTwo.path("items").get(0).path("id").asText());
    assertThat(returnedIds)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrder(
            first.path("id").asText(), second.path("id").asText(), third.path("id").asText());
    assertThat(pageZeroAgain.path("items").get(0).path("id").asText())
        .isEqualTo(pageZero.path("items").get(0).path("id").asText());
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
    String nameJson = objectMapper.writeValueAsString(name);
    String sourceJson = source == null ? "null" : objectMapper.writeValueAsString(source);
    return json(
        post("/api/v1/paychecks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":%s,"source":%s,"amountMinor":%d,"incomeDate":"%s"}
                """
                    .formatted(nameJson, sourceJson, amountMinor, incomeDate)),
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
    insertRepayment(UUID.randomUUID(), ownerId, paybackId, entryId, appliedAt);
  }

  private void insertRepayment(
      UUID repaymentId, UUID ownerId, String paybackId, String entryId, String appliedAt) {
    jdbcTemplate.update(
        """
        insert into payback_repayments
          (id, owner_id, payback_id, entry_id, amount_minor, applied_at, created_at, updated_at, version)
        values (?, ?, ?, ?, 1000, ?, now(), now(), 0)
        """,
        repaymentId,
        ownerId,
        UUID.fromString(paybackId),
        UUID.fromString(entryId),
        Timestamp.from(Instant.parse(appliedAt)));
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
