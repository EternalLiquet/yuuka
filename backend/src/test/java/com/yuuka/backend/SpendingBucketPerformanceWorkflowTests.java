package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class SpendingBucketPerformanceWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void returnsPerPaycheckSummaryForUnderOverExactMixedAndNoBucketCases() throws Exception {
    String token = register("bucket-performance-paycheck@yuuka.local");

    JsonNode mixed = createPaycheck(token, "Mixed", 20000, "2000-01-01");
    String mixedId = mixed.path("id").asText();
    JsonNode bucket = addEntry(token, mixedId, "SPENDING_BUCKET", "Groceries", 10000);
    addEntry(token, mixedId, "BILL", "Rent", 4000);
    addEntry(token, mixedId, "SINKING_FUND", "Tires", 3000);
    addBucketTransaction(token, bucket.path("id").asText(), 2500, "2000-01-01");

    mockMvc
        .perform(get("/api/v1/paychecks/{id}", mixedId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spendingBucketPerformance.budgetedMinor").value(10000))
        .andExpect(jsonPath("$.spendingBucketPerformance.spentMinor").value(2500))
        .andExpect(jsonPath("$.spendingBucketPerformance.netMinor").value(7500));

    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", mixedId)
                .param("type", "BILL")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(1))
        .andExpect(jsonPath("$.spendingBucketPerformance.budgetedMinor").value(10000))
        .andExpect(jsonPath("$.spendingBucketPerformance.spentMinor").value(2500));

    JsonNode over = createPaycheck(token, "Over", 5000, "2000-01-02");
    JsonNode overBucket =
        addEntry(token, over.path("id").asText(), "SPENDING_BUCKET", "Meals", 5000);
    addBucketTransaction(token, overBucket.path("id").asText(), 6200, "2000-01-02");
    JsonNode archived =
        archive(token, over.path("id").asText(), getPaycheck(token, over.path("id").asText()));
    assertThat(archived.path("state").asText()).isEqualTo("ARCHIVED");
    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", over.path("id").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spendingBucketPerformance.netMinor").value(-1200));

    JsonNode exact = createPaycheck(token, "Exact", 5000, "2000-01-03");
    JsonNode exactBucket =
        addEntry(token, exact.path("id").asText(), "SPENDING_BUCKET", "Fuel", 5000);
    addBucketTransaction(token, exactBucket.path("id").asText(), 5000, "2000-01-03");
    JsonNode posted = changeStatus(token, exactBucket.path("id").asText(), 0);
    close(token, exact.path("id").asText(), getPaycheck(token, exact.path("id").asText()));
    assertThat(posted.path("status").asText()).isEqualTo("POSTED");
    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", exact.path("id").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CLOSED"))
        .andExpect(jsonPath("$.spendingBucketPerformance.netMinor").value(0));

    JsonNode noBucket = createPaycheck(token, "No Bucket", 5000, "2000-01-04");
    addEntry(token, noBucket.path("id").asText(), "BILL", "Internet", 5000);
    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", noBucket.path("id").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spendingBucketPerformance").doesNotExist());
  }

  @Test
  void rollsUpClosedAndArchivedBucketsWithinInclusiveWindowWithOwnerIsolation() throws Exception {
    String token = register("bucket-performance-rolling@yuuka.local");
    String otherToken = register("bucket-performance-other@yuuka.local");

    JsonNode start = createPaycheck(token, "Start", 1000, "2026-04-16");
    JsonNode startBucket =
        addEntry(token, start.path("id").asText(), "SPENDING_BUCKET", "Start Bucket", 1000);
    addBucketTransaction(token, startBucket.path("id").asText(), 100, "2026-04-16");
    addBucketTransaction(token, startBucket.path("id").asText(), 900, "2026-07-15");
    changeStatus(token, startBucket.path("id").asText(), 0);
    close(token, start.path("id").asText(), getPaycheck(token, start.path("id").asText()));

    closeBucketPaycheck(token, "End", "2026-07-14", 2000, 200, "2026-07-14");
    closeBucketPaycheck(token, "Too Old", "2026-04-15", 900, 90, "2026-04-15");
    closeBucketPaycheck(token, "Future", "2026-07-15", 800, 80, "2026-07-14");

    JsonNode active = createPaycheck(token, "Still Active", 700, "2026-07-01");
    addEntry(token, active.path("id").asText(), "SPENDING_BUCKET", "Active Bucket", 700);

    JsonNode archived = createPaycheck(token, "Archived", 3000, "2026-06-01");
    JsonNode archivedBucket =
        addEntry(token, archived.path("id").asText(), "SPENDING_BUCKET", "Archived Bucket", 3000);
    addBucketTransaction(token, archivedBucket.path("id").asText(), 400, "2026-06-01");
    archive(token, archived.path("id").asText(), getPaycheck(token, archived.path("id").asText()));

    JsonNode billOnly = createPaycheck(token, "Bill Only", 1000, "2026-06-15");
    JsonNode bill = addEntry(token, billOnly.path("id").asText(), "BILL", "Internet", 1000);
    changeStatus(token, bill.path("id").asText(), 0);
    close(token, billOnly.path("id").asText(), getPaycheck(token, billOnly.path("id").asText()));

    closeBucketPaycheck(otherToken, "Other Owner", "2026-07-01", 999, 999, "2026-07-01");

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .param("asOfDate", "2026-07-14")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-14"))
        .andExpect(jsonPath("$.windowStartDate").value("2026-04-16"))
        .andExpect(jsonPath("$.windowEndDate").value("2026-07-14"))
        .andExpect(jsonPath("$.paycheckCount").value(3))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(6000))
        .andExpect(jsonPath("$.summary.spentMinor").value(700))
        .andExpect(jsonPath("$.summary.netMinor").value(5300));
  }

  @Test
  void reopenedPaycheckDropsOutUntilItIsClosedAgain() throws Exception {
    String token = register("bucket-performance-reopen@yuuka.local");
    JsonNode closed = closeBucketPaycheck(token, "Reopen", "2026-07-01", 1000, 300, "2026-07-01");
    String paycheckId = closed.path("id").asText();

    expectRolling(token, 1000, 300, 700, 1);

    JsonNode reopened =
        json(
            post("/api/v1/paychecks/{id}/reopen", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + closed.path("version").asLong() + "}"),
            200);
    assertThat(reopened.path("state").asText()).isEqualTo("ACTIVE");
    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .param("asOfDate", "2026-07-14")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paycheckCount").value(0))
        .andExpect(jsonPath("$.summary").doesNotExist());

    JsonNode reclosed = close(token, paycheckId, getPaycheck(token, paycheckId));
    assertThat(reclosed.path("state").asText()).isEqualTo("CLOSED");
    expectRolling(token, 1000, 300, 700, 1);
  }

  @Test
  void deletedAndEditedEntriesAndPurchasesRecalculateImmediately() throws Exception {
    String token = register("bucket-performance-edits@yuuka.local");

    JsonNode deletedEntryPaycheck = createPaycheck(token, "Deleted Entry", 1000, "2026-07-01");
    JsonNode deletedEntry =
        addEntry(
            token, deletedEntryPaycheck.path("id").asText(), "SPENDING_BUCKET", "Removed", 1000);
    addBucketTransaction(token, deletedEntry.path("id").asText(), 100, "2026-07-01");
    mockMvc
        .perform(
            delete("/api/v1/entries/{id}", deletedEntry.path("id").asText())
                .param("version", deletedEntry.path("version").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());
    archive(
        token,
        deletedEntryPaycheck.path("id").asText(),
        getPaycheck(token, deletedEntryPaycheck.path("id").asText()));

    JsonNode deletedPurchasePaycheck =
        createPaycheck(token, "Deleted Purchase", 2000, "2026-07-02");
    JsonNode deletedPurchaseBucket =
        addEntry(
            token,
            deletedPurchasePaycheck.path("id").asText(),
            "SPENDING_BUCKET",
            "No Spend",
            2000);
    JsonNode deletedPurchase =
        addBucketTransaction(token, deletedPurchaseBucket.path("id").asText(), 800, "2026-07-02");
    mockMvc
        .perform(
            delete("/api/v1/bucket-transactions/{id}", deletedPurchase.path("id").asText())
                .param("version", deletedPurchase.path("version").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());
    changeStatus(token, deletedPurchaseBucket.path("id").asText(), 0);
    close(
        token,
        deletedPurchasePaycheck.path("id").asText(),
        getPaycheck(token, deletedPurchasePaycheck.path("id").asText()));

    JsonNode editedPaycheck = createPaycheck(token, "Edited", 1500, "2026-07-03");
    JsonNode editedBucket =
        addEntry(token, editedPaycheck.path("id").asText(), "SPENDING_BUCKET", "Edited", 1000);
    JsonNode updatedEntry =
        json(
            patch("/api/v1/entries/{id}", editedBucket.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"SPENDING_BUCKET","name":"Edited","amountMinor":1500,"version":0}
                    """),
            200);
    JsonNode editedPurchase =
        addBucketTransaction(token, editedBucket.path("id").asText(), 900, "2026-07-15");
    json(
        patch("/api/v1/bucket-transactions/{id}", editedPurchase.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"amountMinor":500,"effectiveDate":"2026-07-14","version":%d}
                """
                    .formatted(editedPurchase.path("version").asLong())),
        200);
    changeStatus(token, updatedEntry.path("id").asText(), updatedEntry.path("version").asLong());
    close(
        token,
        editedPaycheck.path("id").asText(),
        getPaycheck(token, editedPaycheck.path("id").asText()));

    expectRolling(token, 3500, 500, 3000, 2);
  }

  private JsonNode closeBucketPaycheck(
      String token, String name, String incomeDate, long budget, long spend, String effectiveDate)
      throws Exception {
    JsonNode paycheck = createPaycheck(token, name, budget, incomeDate);
    JsonNode bucket =
        addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", name + " Bucket", budget);
    addBucketTransaction(token, bucket.path("id").asText(), spend, effectiveDate);
    JsonNode posted = changeStatus(token, bucket.path("id").asText(), 0);
    assertThat(posted.path("status").asText()).isEqualTo("POSTED");
    return close(
        token, paycheck.path("id").asText(), getPaycheck(token, paycheck.path("id").asText()));
  }

  private void expectRolling(
      String token, long budgetedMinor, long spentMinor, long netMinor, long paycheckCount)
      throws Exception {
    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .param("asOfDate", "2026-07-14")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paycheckCount").value(paycheckCount))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(budgetedMinor))
        .andExpect(jsonPath("$.summary.spentMinor").value(spentMinor))
        .andExpect(jsonPath("$.summary.netMinor").value(netMinor));
  }

  private JsonNode createPaycheck(String token, String name, long amount, String incomeDate)
      throws Exception {
    return json(
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

  private JsonNode addEntry(
      String token, String paycheckId, String entryType, String name, long amount)
      throws Exception {
    return json(
        post("/api/v1/paychecks/{id}/entries", paycheckId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"entryType":"%s","name":"%s","amountMinor":%d}
                """
                    .formatted(entryType, name, amount)),
        201);
  }

  private JsonNode addBucketTransaction(
      String token, String entryId, long amount, String effectiveDate) throws Exception {
    return json(
        post("/api/v1/entries/{id}/bucket-transactions", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"amountMinor":%d,"effectiveDate":"%s"}
                """
                    .formatted(amount, effectiveDate)),
        201);
  }

  private JsonNode changeStatus(String token, String entryId, long version) throws Exception {
    return json(
        post("/api/v1/entries/{id}/status", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"POSTED","effectiveAt":"2026-07-14T12:00:00Z","version":%d}
                """
                    .formatted(version)),
        200);
  }

  private JsonNode close(String token, String paycheckId, JsonNode paycheck) throws Exception {
    return json(
        post("/api/v1/paychecks/{id}/close", paycheckId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + paycheck.path("version").asLong() + "}"),
        200);
  }

  private JsonNode archive(String token, String paycheckId, JsonNode paycheck) throws Exception {
    return json(
        delete("/api/v1/paychecks/{id}", paycheckId)
            .param("version", paycheck.path("version").asText())
            .header("Authorization", bearer(token)),
        200);
  }

  private JsonNode getPaycheck(String token, String paycheckId) throws Exception {
    return json(
        get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", bearer(token)), 200);
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
