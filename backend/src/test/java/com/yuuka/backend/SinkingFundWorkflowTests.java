package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class SinkingFundWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createsFundsWithOpeningBalanceAndDerivesBalancesFromTransactions() throws Exception {
    String token = register("sinking-funds-summary@yuuka.local");
    JsonNode fund = createFund(token, "Vacation", 5000, 1000);

    mockMvc
        .perform(get("/api/v1/sinking-funds").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.totalActiveBalanceMinor").value(1000))
        .andExpect(jsonPath("$.summary.activeCount").value(1))
        .andExpect(jsonPath("$.items[0].id").value(fund.path("id").asText()))
        .andExpect(jsonPath("$.items[0].currentBalanceMinor").value(1000))
        .andExpect(jsonPath("$.items[0].remainingTargetMinor").value(4000))
        .andExpect(jsonPath("$.items[0].transactionCount").value(1));

    JsonNode withdrawal =
        withdraw(
            token,
            fund.path("id").asText(),
            getFund(token, fund.path("id").asText()).path("version").asLong(),
            400,
            "2026-07-16",
            "Hotel deposit",
            201);
    assertThat(getFund(token, fund.path("id").asText()).path("currentBalanceMinor").asLong())
        .isEqualTo(600);

    reverseWithdrawal(
        token,
        withdrawal.path("id").asText(),
        withdrawal.path("version").asLong(),
        "Refunded",
        200);

    JsonNode refreshed = getFund(token, fund.path("id").asText());
    assertThat(refreshed.path("currentBalanceMinor").asLong()).isEqualTo(1000);
    assertThat(refreshed.path("transactionCount").asLong()).isEqualTo(2);
  }

  @Test
  void postedLinkedEntriesApplyAndReverseContributions() throws Exception {
    String token = register("sinking-funds-contributions@yuuka.local");
    JsonNode fund = createFund(token, "Car repair", 8000, null);
    JsonNode paycheck = createPaycheck(token, "July", 6000);
    JsonNode entry =
        addEntry(
            token,
            paycheck.path("id").asText(),
            "SINKING_FUND",
            "Car repair contribution",
            3000,
            fund.path("id").asText(),
            201);

    assertThat(entry.path("sinkingFundId").asText()).isEqualTo(fund.path("id").asText());
    assertThat(entry.path("targetMinor").isNull()).isTrue();
    assertThat(getFund(token, fund.path("id").asText()).path("currentBalanceMinor").asLong())
        .isZero();

    JsonNode posted =
        changeStatus(
            token, entry.path("id").asText(), "POSTED", entry.path("version").asLong(), 200);
    assertThat(getFund(token, fund.path("id").asText()).path("currentBalanceMinor").asLong())
        .isEqualTo(3000);
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);

    JsonNode processing =
        changeStatus(
            token, entry.path("id").asText(), "PROCESSING", posted.path("version").asLong(), 200);
    assertThat(getFund(token, fund.path("id").asText()).path("currentBalanceMinor").asLong())
        .isZero();
    assertThat(activeContributionCount(fund.path("id").asText())).isZero();
    assertThat(totalContributionCount(fund.path("id").asText())).isEqualTo(1);

    changeStatus(
        token, entry.path("id").asText(), "POSTED", processing.path("version").asLong(), 200);

    JsonNode transactions =
        json(
            get("/api/v1/sinking-funds/{fundId}/transactions", fund.path("id").asText())
                .header("Authorization", bearer(token)),
            200);
    assertThat(getFund(token, fund.path("id").asText()).path("currentBalanceMinor").asLong())
        .isEqualTo(3000);
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);
    assertThat(totalContributionCount(fund.path("id").asText())).isEqualTo(2);
    assertThat(transactions.path("items").get(0).path("entryName").asText())
        .isEqualTo("Car repair contribution");
    assertThat(transactions.path("items").get(0).path("paycheckName").asText()).isEqualTo("July");
  }

  @Test
  void transactionHistoryPagesWithStableOrderingAndBounds() throws Exception {
    String token = register("sinking-funds-transactions@yuuka.local");
    JsonNode fund = createFund(token, "Appliance", null, 5000);
    updateOpeningBalanceDate(fund.path("id").asText(), "2026-06-01");
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        100,
        "2026-07-01",
        "First",
        201);
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        100,
        "2026-07-03",
        "Third",
        201);
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        100,
        "2026-07-02",
        "Second",
        201);

    mockMvc
        .perform(
            get("/api/v1/sinking-funds/{fundId}/transactions", fund.path("id").asText())
                .param("page", "0")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].reason").value("Third"))
        .andExpect(jsonPath("$.items[1].reason").value("Second"))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalItems").value(4))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(true));

    mockMvc
        .perform(
            get("/api/v1/sinking-funds/{fundId}/transactions", fund.path("id").asText())
                .param("page", "1")
                .param("size", "2")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].reason").value("First"))
        .andExpect(jsonPath("$.items[1].transactionType").value("OPENING_BALANCE"))
        .andExpect(jsonPath("$.hasNext").value(false));

    mockMvc
        .perform(
            get("/api/v1/sinking-funds/{fundId}/transactions", fund.path("id").asText())
                .param("page", "-5")
                .param("size", "0")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].reason").value("Third"))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(1));
  }

  @Test
  void archivesRespectPendingLinksPositiveBalanceConfirmationAndRestore() throws Exception {
    String token = register("sinking-funds-archive@yuuka.local");
    JsonNode fund = createFund(token, "Tuition", null, null);
    JsonNode paycheck = createPaycheck(token, "August", 1000);
    JsonNode entry =
        addEntry(
            token,
            paycheck.path("id").asText(),
            "SINKING_FUND",
            "Tuition contribution",
            1000,
            fund.path("id").asText(),
            201);

    archive(token, fund.path("id").asText(), fund.path("version").asLong(), false, 422);
    assignEntry(token, entry.path("id").asText(), entry.path("version").asLong(), null, 204);
    JsonNode archived =
        archive(
            token,
            fund.path("id").asText(),
            getFund(token, fund.path("id").asText()).path("version").asLong(),
            false,
            200);
    assertThat(archived.path("state").asText()).isEqualTo("ARCHIVED");

    JsonNode restored =
        restore(token, fund.path("id").asText(), archived.path("version").asLong(), 200);
    assertThat(restored.path("state").asText()).isEqualTo("ACTIVE");

    JsonNode funded = createFund(token, "Emergency", null, 1000);
    archive(token, funded.path("id").asText(), funded.path("version").asLong(), false, 422);
    JsonNode archivedWithBalance =
        archive(
            token,
            funded.path("id").asText(),
            getFund(token, funded.path("id").asText()).path("version").asLong(),
            true,
            200);
    assertThat(archivedWithBalance.path("state").asText()).isEqualTo("ARCHIVED");
    assertThat(archivedWithBalance.path("currentBalanceMinor").asLong()).isEqualTo(1000);
  }

  @Test
  void ownerScopedReadsAndAssignmentDoNotLeakFunds() throws Exception {
    String ownerToken = register("sinking-funds-owner@yuuka.local");
    String otherToken = register("sinking-funds-other@yuuka.local");
    JsonNode ownerFund = createFund(ownerToken, "Private", null, null);
    JsonNode otherPaycheck = createPaycheck(otherToken, "Other", 1000);

    mockMvc
        .perform(
            get("/api/v1/sinking-funds/{fundId}", ownerFund.path("id").asText())
                .header("Authorization", bearer(otherToken)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/sinking-funds").header("Authorization", bearer(otherToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.summary.totalActiveBalanceMinor").value(0));

    addEntry(
        otherToken,
        otherPaycheck.path("id").asText(),
        "SINKING_FUND",
        "Cross-owner attempt",
        1000,
        ownerFund.path("id").asText(),
        404);
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

  private JsonNode createFund(
      String token, String name, Integer targetMinor, Integer openingBalanceMinor)
      throws Exception {
    return json(
        post("/api/v1/sinking-funds")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "name":%s,
                  "targetMinor":%s,
                  "targetDate":"2026-12-31",
                  "notes":"steady progress",
                  "openingBalanceMinor":%s
                }
                """
                    .formatted(
                        objectMapper.writeValueAsString(name),
                        nullableNumber(targetMinor),
                        nullableNumber(openingBalanceMinor))),
        201);
  }

  private JsonNode getFund(String token, String fundId) throws Exception {
    return json(
        get("/api/v1/sinking-funds/{fundId}", fundId).header("Authorization", bearer(token)), 200);
  }

  private JsonNode createPaycheck(String token, String name, long amountMinor) throws Exception {
    return json(
        post("/api/v1/paychecks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":%s,"source":null,"amountMinor":%d,"incomeDate":"2026-07-17"}
                """
                    .formatted(objectMapper.writeValueAsString(name), amountMinor)),
        201);
  }

  private JsonNode addEntry(
      String token,
      String paycheckId,
      String entryType,
      String name,
      long amountMinor,
      String sinkingFundId,
      int expectedStatus)
      throws Exception {
    String sinkingFundJson = sinkingFundId == null ? "null" : "\"%s\"".formatted(sinkingFundId);
    return json(
        post("/api/v1/paychecks/{id}/entries", paycheckId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "entryType":"%s",
                  "name":%s,
                  "amountMinor":%d,
                  "paybackId":null,
                  "sinkingFundId":%s,
                  "targetMinor":9999,
                  "targetDate":"2026-12-31"
                }
                """
                    .formatted(
                        entryType,
                        objectMapper.writeValueAsString(name),
                        amountMinor,
                        sinkingFundJson)),
        expectedStatus);
  }

  private JsonNode changeStatus(
      String token, String entryId, String requestedStatus, long version, int expectedStatus)
      throws Exception {
    return json(
        post("/api/v1/entries/{id}/status", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"%s","effectiveAt":"2026-07-17T12:00:00Z","version":%d}
                """
                    .formatted(requestedStatus, version)),
        expectedStatus);
  }

  private JsonNode withdraw(
      String token,
      String fundId,
      long version,
      long amountMinor,
      String effectiveDate,
      String reason,
      int expectedStatus)
      throws Exception {
    return json(
        post("/api/v1/sinking-funds/{fundId}/withdrawals", fundId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "amountMinor":%d,
                  "effectiveDate":"%s",
                  "reason":%s,
                  "notes":null,
                  "version":%d
                }
                """
                    .formatted(
                        amountMinor,
                        effectiveDate,
                        objectMapper.writeValueAsString(reason),
                        version)),
        expectedStatus);
  }

  private JsonNode reverseWithdrawal(
      String token, String transactionId, long version, String reason, int expectedStatus)
      throws Exception {
    return json(
        post("/api/v1/sinking-fund-transactions/{transactionId}/reverse", transactionId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"reason":%s,"version":%d}
                """
                    .formatted(objectMapper.writeValueAsString(reason), version)),
        expectedStatus);
  }

  private JsonNode archive(
      String token, String fundId, long version, boolean confirmPositiveBalance, int expectedStatus)
      throws Exception {
    return json(
        post("/api/v1/sinking-funds/{fundId}/archive", fundId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"version":%d,"confirmPositiveBalance":%s}
                """
                    .formatted(version, confirmPositiveBalance)),
        expectedStatus);
  }

  private JsonNode restore(String token, String fundId, long version, int expectedStatus)
      throws Exception {
    return json(
        post("/api/v1/sinking-funds/{fundId}/restore", fundId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"version":%d,"confirmPositiveBalance":false}
                """
                    .formatted(version)),
        expectedStatus);
  }

  private void assignEntry(
      String token, String entryId, long version, String sinkingFundId, int expectedStatus)
      throws Exception {
    String sinkingFundJson = sinkingFundId == null ? "null" : "\"%s\"".formatted(sinkingFundId);
    json(
        post("/api/v1/entries/{entryId}/sinking-fund-assignment", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"sinkingFundId":%s,"version":%d}
                """
                    .formatted(sinkingFundJson, version)),
        expectedStatus);
  }

  private void updateOpeningBalanceDate(String fundId, String effectiveDate) {
    jdbcTemplate.update(
        """
        update sinking_fund_transactions
        set effective_date = ?
        where sinking_fund_id = ? and transaction_type = 'OPENING_BALANCE'
        """,
        java.sql.Date.valueOf(effectiveDate),
        UUID.fromString(fundId));
  }

  private long activeContributionCount(String fundId) {
    return jdbcTemplate.queryForObject(
        """
        select count(*) from sinking_fund_transactions
        where sinking_fund_id = ? and transaction_type = 'CONTRIBUTION' and reversed_at is null
        """,
        Long.class,
        UUID.fromString(fundId));
  }

  private long totalContributionCount(String fundId) {
    return jdbcTemplate.queryForObject(
        """
        select count(*) from sinking_fund_transactions
        where sinking_fund_id = ? and transaction_type = 'CONTRIBUTION'
        """,
        Long.class,
        UUID.fromString(fundId));
  }

  private JsonNode json(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
  }

  private String nullableNumber(Integer value) {
    return value == null ? "null" : value.toString();
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
