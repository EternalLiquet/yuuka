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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class SpendingBucketPerformanceWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MutableClock clock;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void resetClock() {
    clock.setInstant(Instant.parse("2026-07-15T03:59:00Z"));
  }

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
  void usesOwnerLocalDateForPerPaycheckSummaryAtUtcBoundary() throws Exception {
    String token = register("bucket-performance-local-date@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Local Date", 2000, "2026-07-14");
    String paycheckId = paycheck.path("id").asText();
    JsonNode bucket = addEntry(token, paycheckId, "SPENDING_BUCKET", "Boundary Bucket", 2000);
    String bucketId = bucket.path("id").asText();
    addBucketTransaction(token, bucketId, 500, "2026-07-14");
    addBucketTransaction(token, bucketId, 700, "2026-07-15");
    changeStatus(token, bucketId, 0);

    mockMvc
        .perform(get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spendingBucketPerformance.budgetedMinor").value(2000))
        .andExpect(jsonPath("$.spendingBucketPerformance.spentMinor").value(500))
        .andExpect(jsonPath("$.spendingBucketPerformance.netMinor").value(1500));
    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-14"))
        .andExpect(jsonPath("$.windowStartDate").value("2026-04-16"))
        .andExpect(jsonPath("$.windowEndDate").value("2026-07-14"))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(2000))
        .andExpect(jsonPath("$.summary.spentMinor").value(500))
        .andExpect(jsonPath("$.summary.netMinor").value(1500));
    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .param("asOfDate", "2026-07-15")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-15"))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(2000))
        .andExpect(jsonPath("$.summary.spentMinor").value(1200))
        .andExpect(jsonPath("$.summary.netMinor").value(800));

    clock.setInstant(Instant.parse("2026-07-15T04:01:00Z"));

    mockMvc
        .perform(get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.spendingBucketPerformance.budgetedMinor").value(2000))
        .andExpect(jsonPath("$.spendingBucketPerformance.spentMinor").value(1200))
        .andExpect(jsonPath("$.spendingBucketPerformance.netMinor").value(800));
    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-15"))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(2000))
        .andExpect(jsonPath("$.summary.spentMinor").value(1200))
        .andExpect(jsonPath("$.summary.netMinor").value(800));
    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .param("asOfDate", "2026-07-14")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-14"))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(2000))
        .andExpect(jsonPath("$.summary.spentMinor").value(500))
        .andExpect(jsonPath("$.summary.netMinor").value(1500));
  }

  @Test
  void rollsUpActiveClosedArchivedAndLegacyCompletedActiveBucketsWithinInclusiveWindow()
      throws Exception {
    String token = register("bucket-performance-rolling@yuuka.local");
    String otherToken = register("bucket-performance-other@yuuka.local");

    JsonNode start = createPaycheck(token, "Start", 1000, "2026-04-16");
    JsonNode startBucket =
        addEntry(token, start.path("id").asText(), "SPENDING_BUCKET", "Start Bucket", 1000);
    addBucketTransaction(token, startBucket.path("id").asText(), 100, "2026-04-16");
    addBucketTransaction(token, startBucket.path("id").asText(), 900, "2026-07-15");
    changeStatus(token, startBucket.path("id").asText(), 0);

    closeBucketPaycheck(token, "End", "2026-07-14", 2000, 200, "2026-07-14");
    closeBucketPaycheck(token, "Too Old", "2026-04-15", 900, 90, "2026-04-15");
    closeBucketPaycheck(token, "Future", "2026-07-15", 800, 80, "2026-07-14");
    closeBucketPaycheck(token, "No Purchases Closed", "2026-07-10", 500, 0, null);

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

    JsonNode legacy =
        closeBucketPaycheck(token, "Legacy Active", "2026-07-11", 600, 60, "2026-07-11");
    jdbcTemplate.update(
        "update paychecks set state = 'ACTIVE', closed_at = null where id = ?",
        UUID.fromString(legacy.path("id").asText()));

    closeBucketPaycheck(otherToken, "Other Owner", "2026-07-01", 999, 999, "2026-07-01");

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling")
                .param("days", "90")
                .param("asOfDate", "2026-07-14")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-14"))
        .andExpect(jsonPath("$.windowStartDate").value("2026-04-16"))
        .andExpect(jsonPath("$.windowEndDate").value("2026-07-14"))
        .andExpect(jsonPath("$.paycheckCount").value(6))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(7800))
        .andExpect(jsonPath("$.summary.spentMinor").value(760))
        .andExpect(jsonPath("$.summary.netMinor").value(7040));

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling-90-days")
                .param("asOfDate", "2026-07-14")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowStartDate").value("2026-04-16"))
        .andExpect(jsonPath("$.summary.netMinor").value(7040));
  }

  @Test
  void rollsUpThirtyAndNinetyDayWindowsWithInclusiveBoundariesAndExclusions() throws Exception {
    String token = register("bucket-performance-periods@yuuka.local");

    JsonNode active29 = createPaycheck(token, "Active 29 Days Ago", 1000, "2026-06-16");
    JsonNode active29Bucket =
        addEntry(
            token, active29.path("id").asText(), "SPENDING_BUCKET", "Active Boundary Bucket", 1000);
    addBucketTransaction(token, active29Bucket.path("id").asText(), 100, "2026-06-16");

    closeBucketPaycheck(token, "Closed In Window", "2026-07-01", 2000, 200, "2026-07-01");

    JsonNode archived = createPaycheck(token, "Archived In Window", 3000, "2026-07-10");
    JsonNode archivedBucket =
        addEntry(token, archived.path("id").asText(), "SPENDING_BUCKET", "Archived Bucket", 3000);
    addBucketTransaction(token, archivedBucket.path("id").asText(), 300, "2026-07-10");
    archive(token, archived.path("id").asText(), getPaycheck(token, archived.path("id").asText()));

    JsonNode deletedPurchasePaycheck =
        createPaycheck(token, "Deleted Purchase Still Budgeted", 500, "2026-07-11");
    JsonNode deletedPurchaseBucket =
        addEntry(
            token,
            deletedPurchasePaycheck.path("id").asText(),
            "SPENDING_BUCKET",
            "Deleted Purchase Bucket",
            500);
    JsonNode deletedPurchase =
        addBucketTransaction(token, deletedPurchaseBucket.path("id").asText(), 450, "2026-07-11");
    mockMvc
        .perform(
            delete("/api/v1/bucket-transactions/{id}", deletedPurchase.path("id").asText())
                .param("version", deletedPurchase.path("version").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());

    JsonNode deletedEntryPaycheck =
        createPaycheck(token, "Deleted Entry Excluded", 700, "2026-07-12");
    JsonNode deletedEntry =
        addEntry(
            token,
            deletedEntryPaycheck.path("id").asText(),
            "SPENDING_BUCKET",
            "Deleted Entry Bucket",
            700);
    addBucketTransaction(token, deletedEntry.path("id").asText(), 70, "2026-07-12");
    mockMvc
        .perform(
            delete("/api/v1/entries/{id}", deletedEntry.path("id").asText())
                .param("version", deletedEntry.path("version").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());

    closeBucketPaycheck(token, "Thirty Days Ago", "2026-06-15", 4000, 400, "2026-06-15");
    closeBucketPaycheck(token, "Ninety Day Start", "2026-04-17", 6000, 600, "2026-04-17");
    closeBucketPaycheck(token, "Too Old For Ninety", "2026-04-16", 8000, 800, "2026-04-16");
    closeBucketPaycheck(token, "Future Income", "2026-07-16", 9000, 900, "2026-07-15");

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling")
                .param("days", "30")
                .param("asOfDate", "2026-07-15")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-15"))
        .andExpect(jsonPath("$.windowStartDate").value("2026-06-16"))
        .andExpect(jsonPath("$.windowEndDate").value("2026-07-15"))
        .andExpect(jsonPath("$.paycheckCount").value(4))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(6500))
        .andExpect(jsonPath("$.summary.spentMinor").value(600))
        .andExpect(jsonPath("$.summary.netMinor").value(5900));

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling")
                .param("asOfDate", "2026-07-15")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowStartDate").value("2026-06-16"))
        .andExpect(jsonPath("$.summary.netMinor").value(5900));

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling")
                .param("days", "90")
                .param("asOfDate", "2026-07-15")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-15"))
        .andExpect(jsonPath("$.windowStartDate").value("2026-04-17"))
        .andExpect(jsonPath("$.windowEndDate").value("2026-07-15"))
        .andExpect(jsonPath("$.paycheckCount").value(6))
        .andExpect(jsonPath("$.summary.budgetedMinor").value(16500))
        .andExpect(jsonPath("$.summary.spentMinor").value(1600))
        .andExpect(jsonPath("$.summary.netMinor").value(14900));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "60", "365"})
  void rejectsUnsupportedRollingWindowDays(String days) throws Exception {
    String token = register("bucket-performance-unsupported-" + days + "@yuuka.local");

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling")
                .param("days", days)
                .param("asOfDate", "2026-07-15")
                .header("Authorization", bearer(token)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("days must be 30 or 90."));
  }

  @Test
  void rejectsMalformedRollingWindowDays() throws Exception {
    String token = register("bucket-performance-malformed-days@yuuka.local");

    mockMvc
        .perform(
            get("/api/v1/spending-buckets/performance/rolling")
                .param("days", "thirty")
                .param("asOfDate", "2026-07-15")
                .header("Authorization", bearer(token)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("The request could not be completed."))
        .andExpect(jsonPath("$.fieldErrors.days").value("must be a valid integer"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void perPaycheckSpentTotalAtInt64LimitWorksWithoutWrappingNegativeNet() throws Exception {
    String token = register("bucket-paycheck-spent-limit@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Huge Purchase", 1, "2026-07-14");
    JsonNode bucket =
        addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", "Tiny Budget", 1);
    addBucketTransaction(token, bucket.path("id").asText(), Long.MAX_VALUE, "2026-07-14");

    JsonNode response =
        json(
            get("/api/v1/paychecks/{id}", paycheck.path("id").asText())
                .header("Authorization", bearer(token)),
            200);

    assertThat(response.path("spendingBucketPerformance").path("budgetedMinor").asLong())
        .isEqualTo(1);
    assertThat(response.path("spendingBucketPerformance").path("spentMinor").asLong())
        .isEqualTo(Long.MAX_VALUE);
    assertThat(response.path("spendingBucketPerformance").path("netMinor").asLong())
        .isEqualTo(1L - Long.MAX_VALUE);
  }

  @Test
  void perPaycheckSpentTotalOverflowUsesBusinessRuleEnvelope() throws Exception {
    String token = register("bucket-paycheck-spent-overflow@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Per Paycheck Overflow", 2, "2026-07-14");
    JsonNode first = addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", "First", 1);
    JsonNode second = addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", "Second", 1);
    addBucketTransaction(token, first.path("id").asText(), Long.MAX_VALUE, "2026-07-14");
    addBucketTransaction(token, second.path("id").asText(), 1, "2026-07-14");

    JsonNode error =
        json(
            get("/api/v1/paychecks/{id}", paycheck.path("id").asText())
                .header("Authorization", bearer(token)),
            422);

    assertOverflowEnvelope(error);
  }

  @Test
  void rollingBudgetTotalAtInt64LimitWorksAndIgnoresOtherOwners() throws Exception {
    String token = register("bucket-rolling-budget-limit@yuuka.local");
    String otherToken = register("bucket-rolling-budget-other@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Limit Budget", Long.MAX_VALUE, "2026-07-14");
    addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", "Limit", Long.MAX_VALUE);
    JsonNode otherPaycheck = createPaycheck(otherToken, "Other Budget", 1, "2026-07-14");
    addEntry(otherToken, otherPaycheck.path("id").asText(), "SPENDING_BUCKET", "Other", 1);

    JsonNode response = rollingPerformance(token, 200);

    assertThat(response.path("paycheckCount").asLong()).isEqualTo(1);
    assertThat(response.path("summary").path("budgetedMinor").asLong()).isEqualTo(Long.MAX_VALUE);
    assertThat(response.path("summary").path("spentMinor").asLong()).isZero();
    assertThat(response.path("summary").path("netMinor").asLong()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void rollingBudgetTotalOverflowUsesBusinessRuleEnvelope() throws Exception {
    String token = register("bucket-rolling-budget-overflow@yuuka.local");
    JsonNode first = createPaycheck(token, "Huge Budget", Long.MAX_VALUE, "2026-07-14");
    addEntry(token, first.path("id").asText(), "SPENDING_BUCKET", "Huge", Long.MAX_VALUE);
    JsonNode second = createPaycheck(token, "Small Budget", 1, "2026-07-15");
    addEntry(token, second.path("id").asText(), "SPENDING_BUCKET", "Small", 1);

    JsonNode error = rollingPerformance(token, 422);

    assertOverflowEnvelope(error);
  }

  @Test
  void rollingSpentTotalAtInt64LimitWorksAndIgnoresOtherOwners() throws Exception {
    String token = register("bucket-rolling-spent-limit@yuuka.local");
    String otherToken = register("bucket-rolling-spent-other@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Huge Spent", 1, "2026-07-14");
    JsonNode bucket = addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", "Tiny", 1);
    addBucketTransaction(token, bucket.path("id").asText(), Long.MAX_VALUE, "2026-07-14");
    JsonNode otherPaycheck = createPaycheck(otherToken, "Other Spent", 1, "2026-07-14");
    JsonNode otherBucket =
        addEntry(otherToken, otherPaycheck.path("id").asText(), "SPENDING_BUCKET", "Other", 1);
    addBucketTransaction(otherToken, otherBucket.path("id").asText(), 1, "2026-07-14");

    JsonNode response = rollingPerformance(token, 200);

    assertThat(response.path("summary").path("budgetedMinor").asLong()).isEqualTo(1);
    assertThat(response.path("summary").path("spentMinor").asLong()).isEqualTo(Long.MAX_VALUE);
    assertThat(response.path("summary").path("netMinor").asLong()).isEqualTo(1L - Long.MAX_VALUE);
  }

  @Test
  void rollingSpentTotalOverflowUsesBusinessRuleEnvelope() throws Exception {
    String token = register("bucket-rolling-spent-overflow@yuuka.local");
    JsonNode first = createPaycheck(token, "Huge Spent", 1, "2026-07-14");
    JsonNode firstBucket = addEntry(token, first.path("id").asText(), "SPENDING_BUCKET", "Huge", 1);
    addBucketTransaction(token, firstBucket.path("id").asText(), Long.MAX_VALUE, "2026-07-14");
    JsonNode second = createPaycheck(token, "Small Spent", 1, "2026-07-15");
    JsonNode secondBucket =
        addEntry(token, second.path("id").asText(), "SPENDING_BUCKET", "Small", 1);
    addBucketTransaction(token, secondBucket.path("id").asText(), 1, "2026-07-15");

    JsonNode error = rollingPerformance(token, 422);

    assertOverflowEnvelope(error);
  }

  @Test
  void reopenedPaycheckRemainsInRollingSnapshotUntilExplicitClose() throws Exception {
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
    expectRolling(token, 1000, 300, 700, 1);

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

    expectRolling(token, 3500, 500, 3000, 2);
  }

  private JsonNode closeBucketPaycheck(
      String token, String name, String incomeDate, long budget, long spend, String effectiveDate)
      throws Exception {
    JsonNode paycheck = createPaycheck(token, name, budget, incomeDate);
    JsonNode bucket =
        addEntry(token, paycheck.path("id").asText(), "SPENDING_BUCKET", name + " Bucket", budget);
    if (effectiveDate != null) {
      addBucketTransaction(token, bucket.path("id").asText(), spend, effectiveDate);
    }
    JsonNode posted = changeStatus(token, bucket.path("id").asText(), 0);
    assertThat(posted.path("status").asText()).isEqualTo("POSTED");
    JsonNode completed = getPaycheck(token, paycheck.path("id").asText());
    assertThat(completed.path("state").asText()).isEqualTo("CLOSED");
    return completed;
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

  private JsonNode rollingPerformance(String token, int expectedStatus) throws Exception {
    return json(
        get("/api/v1/spending-buckets/performance/rolling")
            .param("days", "90")
            .param("asOfDate", "2026-07-15")
            .header("Authorization", bearer(token)),
        expectedStatus);
  }

  private void assertOverflowEnvelope(JsonNode error) {
    assertThat(error.path("code").asText()).isEqualTo("MONEY_AMOUNT_OVERFLOW");
    assertThat(error.path("message").asText())
        .isEqualTo("The amounts in this request are too large to calculate safely.");
    assertThat(error.path("fieldErrors").isObject()).isTrue();
    assertThat(error.path("details").path("currencyCode").asText()).isEqualTo("USD");
    assertThat(error.path("traceId").asText()).isNotBlank();
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

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2026-07-15T03:59:00Z"));
    }
  }

  static class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
