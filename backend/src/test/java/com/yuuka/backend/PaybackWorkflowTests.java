package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class PaybackWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createsPaybacksWithOpeningBalancesAndSummarizesOnlyActiveRemaining() throws Exception {
    String token = registerAndGetAccessToken("paybacks-summary@yuuka.local");

    createPayback(token, "Personal loan", 200000, 125000);
    createPayback(token, "Already settled", 50000, 0);

    mockMvc
        .perform(get("/api/v1/paybacks").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.totalRemainingMinor").value(125000))
        .andExpect(jsonPath("$.summary.totalOriginalMinor").value(250000))
        .andExpect(jsonPath("$.summary.activeCount").value(1))
        .andExpect(jsonPath("$.items[0].state").value("ACTIVE"))
        .andExpect(jsonPath("$.items[1].state").value("PAID_OFF"));
  }

  @Test
  void appliesAndReversesPostedEntryRepaymentsExactlyOnce() throws Exception {
    String token = registerAndGetAccessToken("paybacks-lifecycle@yuuka.local");
    JsonNode payback = createPayback(token, "Car repair", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "Repair repayment",
            10000,
            payback.path("id").asText());

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}", payback.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repaidMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(10000))
        .andExpect(jsonPath("$.state").value("ACTIVE"));

    JsonNode posted =
        changeStatus(token, entry.path("id").asText(), "POSTED", entry.path("version").asLong());
    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}", payback.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repaidMinor").value(10000))
        .andExpect(jsonPath("$.remainingMinor").value(0))
        .andExpect(jsonPath("$.state").value("PAID_OFF"))
        .andExpect(jsonPath("$.repaymentCount").value(1));

    changeStatus(token, entry.path("id").asText(), "POSTED", posted.path("version").asLong(), 422);
    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}", payback.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repaidMinor").value(10000))
        .andExpect(jsonPath("$.remainingMinor").value(0));

    JsonNode processing =
        changeStatus(
            token, entry.path("id").asText(), "PROCESSING", posted.path("version").asLong());
    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}", payback.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repaidMinor").value(0))
        .andExpect(jsonPath("$.remainingMinor").value(10000))
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        .andExpect(jsonPath("$.repaymentCount").value(1));

    changeStatus(token, entry.path("id").asText(), "POSTED", processing.path("version").asLong());
    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}/repayments", payback.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.items[0].reversedAt").isEmpty())
        .andExpect(jsonPath("$.items[1].reversedAt").isNotEmpty());
  }

  @Test
  void repaymentApplyAndReverseAdvancePaybackVersionEvenWhenStillActive() throws Exception {
    String token = registerAndGetAccessToken("paybacks-version@yuuka.local");
    JsonNode payback = createPayback(token, "Partial repayment", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry =
        createEntry(
            token, paycheck.path("id").asText(), "Partial", 4000, payback.path("id").asText());

    long initialVersion = payback.path("version").asLong();
    JsonNode posted =
        changeStatus(token, entry.path("id").asText(), "POSTED", entry.path("version").asLong());
    JsonNode afterPost = getPayback(token, payback.path("id").asText());

    assertThat(afterPost.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(afterPost.path("version").asLong()).isGreaterThan(initialVersion);
    assertThat(afterPost.path("remainingMinor").asLong()).isEqualTo(6000);

    JsonNode processing =
        changeStatus(
            token, entry.path("id").asText(), "PROCESSING", posted.path("version").asLong());
    JsonNode afterReverse = getPayback(token, payback.path("id").asText());

    assertThat(processing.path("status").asText()).isEqualTo("PROCESSING");
    assertThat(afterReverse.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(afterReverse.path("version").asLong())
        .isGreaterThan(afterPost.path("version").asLong());
    assertThat(afterReverse.path("remainingMinor").asLong()).isEqualTo(10000);
  }

  @Test
  void lockedBaselineUpdateCannotFallBelowRecordedRepayments() throws Exception {
    String token = registerAndGetAccessToken("paybacks-baseline@yuuka.local");
    JsonNode payback = createPayback(token, "Baseline", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry =
        createEntry(
            token, paycheck.path("id").asText(), "Repayment", 4000, payback.path("id").asText());
    changeStatus(token, entry.path("id").asText(), "POSTED", entry.path("version").asLong());
    JsonNode current = getPayback(token, payback.path("id").asText());

    JsonNode exactBaseline =
        updatePayback(
            token,
            payback.path("id").asText(),
            "Baseline",
            10000,
            4000,
            current.path("version").asLong(),
            200);

    assertThat(exactBaseline.path("remainingMinor").asLong()).isZero();
    assertThat(exactBaseline.path("state").asText()).isEqualTo("PAID_OFF");

    JsonNode rejected =
        updatePayback(
            token,
            payback.path("id").asText(),
            "Baseline",
            10000,
            3999,
            exactBaseline.path("version").asLong(),
            422);
    assertThat(rejected.path("code").asText()).isEqualTo("PAYBACK_BASELINE_BELOW_REPAYMENTS");
    assertThat(rejected.path("details").path("amountMinor").asLong()).isEqualTo(1);
  }

  @Test
  void staleBaselineUpdateAfterRepaymentIsRejectedByAdvancedVersion() throws Exception {
    String token = registerAndGetAccessToken("paybacks-stale-boundary@yuuka.local");
    JsonNode payback = createPayback(token, "Boundary", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry =
        createEntry(
            token, paycheck.path("id").asText(), "Repayment", 4000, payback.path("id").asText());

    changeStatus(token, entry.path("id").asText(), "POSTED", entry.path("version").asLong());

    updatePayback(
        token,
        payback.path("id").asText(),
        "Boundary",
        10000,
        10000,
        payback.path("version").asLong(),
        409);
  }

  @Test
  void databaseRejectsCrossOwnerPaybackRelationships() throws Exception {
    String ownerToken = registerAndGetAccessToken("paybacks-db-owner@yuuka.local");
    String otherToken = registerAndGetAccessToken("paybacks-db-other@yuuka.local");
    JsonNode payback = createPayback(ownerToken, "Owner Payback", 10000, 10000);
    JsonNode otherPaycheck = createPaycheck(otherToken, 10000);
    JsonNode otherEntry =
        createEntry(otherToken, otherPaycheck.path("id").asText(), "Other entry", 1000, null);
    UUID paybackId = UUID.fromString(payback.path("id").asText());
    UUID otherOwnerId =
        jdbcTemplate.queryForObject(
            "select owner_id from paycheck_entries where id = ?",
            UUID.class,
            UUID.fromString(otherEntry.path("id").asText()));
    UUID ownerId =
        jdbcTemplate.queryForObject(
            "select owner_id from paybacks where id = ?", UUID.class, paybackId);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update paycheck_entries set payback_id = ? where id = ?",
                    paybackId,
                    UUID.fromString(otherEntry.path("id").asText())))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into payback_repayments
                        (id, owner_id, payback_id, entry_id, amount_minor, applied_at, created_at, updated_at, version)
                    values (?, ?, ?, ?, 1000, now(), now(), now(), 0)
                    """,
                    UUID.randomUUID(),
                    otherOwnerId,
                    paybackId,
                    UUID.fromString(otherEntry.path("id").asText())))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into payback_repayments
                        (id, owner_id, payback_id, entry_id, amount_minor, applied_at, created_at, updated_at, version)
                    values (?, ?, ?, ?, 1000, now(), now(), now(), 0)
                    """,
                    UUID.randomUUID(),
                    ownerId,
                    paybackId,
                    UUID.fromString(otherEntry.path("id").asText())))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void rejectsOverpaymentWithStructuredMoneyDetails() throws Exception {
    String token = registerAndGetAccessToken("paybacks-overpay@yuuka.local");
    JsonNode payback = createPayback(token, "Small Payback", 5000, 5000);
    JsonNode paycheck = createPaycheck(token, 10000);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paychecks/{id}/entries", paycheck.path("id").asText())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "entryType": "BILL",
                          "name": "Too much",
                          "amountMinor": 7500,
                          "paybackId": "%s"
                        }
                        """
                            .formatted(payback.path("id").asText())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYBACK_REPAYMENT_OVERPAID"))
            .andExpect(jsonPath("$.details.amountMinor").value(2500))
            .andExpect(jsonPath("$.details.currencyCode").value("USD"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).doesNotContain("minor unit");
  }

  @Test
  void protectsPaybacksFromCrossOwnerAccess() throws Exception {
    String ownerToken = registerAndGetAccessToken("paybacks-owner@yuuka.local");
    String otherToken = registerAndGetAccessToken("paybacks-other@yuuka.local");
    JsonNode payback = createPayback(ownerToken, "Private Payback", 10000, 10000);
    JsonNode otherPaycheck = createPaycheck(otherToken, 10000);

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}", payback.path("id").asText())
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/entries", otherPaycheck.path("id").asText())
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType": "BILL",
                      "name": "Cross owner",
                      "amountMinor": 1000,
                      "paybackId": "%s"
                    }
                    """
                        .formatted(payback.path("id").asText())))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsOpeningRemainingAboveOriginal() throws Exception {
    String token = registerAndGetAccessToken("paybacks-validation@yuuka.local");

    mockMvc
        .perform(
            post("/api/v1/paybacks")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "Invalid",
                      "originalAmountMinor": 1000,
                      "openingRemainingAmountMinor": 1001,
                      "borrowedDate": "2026-07-12"
                    }
                    """))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PAYBACK_OPENING_EXCEEDS_ORIGINAL"));
  }

  private JsonNode createPayback(
      String token, String name, long originalAmount, long openingRemaining) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paybacks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "originalAmountMinor": %d,
                          "openingRemainingAmountMinor": %d,
                          "borrowedDate": "2026-07-12"
                        }
                        """
                            .formatted(name, originalAmount, openingRemaining)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode createPaycheck(String token, long amountMinor) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Payback check","amountMinor":%d,"incomeDate":"2026-07-17"}
                        """
                            .formatted(amountMinor)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode createEntry(
      String token, String paycheckId, String name, long amountMinor, String paybackId)
      throws Exception {
    String paybackJson = paybackId == null ? "null" : "\"%s\"".formatted(paybackId);
    var action =
        mockMvc
            .perform(
                post("/api/v1/paychecks/{id}/entries", paycheckId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "entryType": "BILL",
                          "name": "%s",
                          "amountMinor": %d,
                          "paybackId": %s
                        }
                        """
                            .formatted(name, amountMinor, paybackJson)))
            .andExpect(status().isCreated());
    if (paybackId == null) {
      action.andExpect(jsonPath("$.paybackId").isEmpty());
    } else {
      action.andExpect(jsonPath("$.paybackId").value(paybackId));
    }
    MvcResult result = action.andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode getPayback(String token, String paybackId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/paybacks/{id}", paybackId).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode updatePayback(
      String token,
      String paybackId,
      String name,
      long originalAmount,
      long openingRemaining,
      long version,
      int expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                patch("/api/v1/paybacks/{id}", paybackId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "originalAmountMinor": %d,
                          "openingRemainingAmountMinor": %d,
                          "borrowedDate": "2026-07-12",
                          "version": %d
                        }
                        """
                            .formatted(name, originalAmount, openingRemaining, version)))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode changeStatus(String token, String entryId, String requestedStatus, long version)
      throws Exception {
    return changeStatus(token, entryId, requestedStatus, version, 200);
  }

  private JsonNode changeStatus(
      String token, String entryId, String requestedStatus, long version, int expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/entries/{id}/status", entryId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"toStatus":"%s","effectiveAt":"2026-07-17T12:00:00Z","version":%d}
                        """
                            .formatted(requestedStatus, version)))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private String registerAndGetAccessToken(String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"%s","password":"Password12345","displayName":"Test"}
                        """
                            .formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .path("accessToken")
        .asText();
  }
}
