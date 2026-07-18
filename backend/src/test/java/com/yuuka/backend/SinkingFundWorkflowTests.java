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
    updateEntry(
        token,
        entry.path("id").asText(),
        entry.path("version").asLong(),
        "SINKING_FUND",
        entry.path("name").asText(),
        entry.path("amountMinor").asLong(),
        null,
        200);
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

  @Test
  void exactLongMaxBalanceIsReturnedButAdditionalPostedContributionOverflowsAndRollsBack()
      throws Exception {
    String token = register("sinking-funds-long-max@yuuka.local");
    JsonNode fund = createFund(token, "Vault", null, Long.MAX_VALUE);
    assertThat(getFund(token, fund.path("id").asText()).path("currentBalanceMinor").asLong())
        .isEqualTo(Long.MAX_VALUE);

    JsonNode list = json(get("/api/v1/sinking-funds").header("Authorization", bearer(token)), 200);
    assertThat(list.path("summary").path("totalActiveBalanceMinor").asLong())
        .isEqualTo(Long.MAX_VALUE);

    JsonNode paycheck = createPaycheck(token, "Overflow check", 1);
    JsonNode entry =
        addEntry(
            token,
            paycheck.path("id").asText(),
            "SINKING_FUND",
            "Too much",
            1,
            fund.path("id").asText(),
            201);
    long transactionCountBefore = sinkingFundTransactionCount(fund.path("id").asText());
    long statusEventCountBefore = statusEventCount(entry.path("id").asText());
    long auditCountBefore =
        auditCount("PAYCHECK_ENTRY", UUID.fromString(entry.path("id").asText()));

    JsonNode error =
        changeStatus(
            token, entry.path("id").asText(), "POSTED", entry.path("version").asLong(), 422);

    assertThat(error.path("code").asText()).isEqualTo("MONEY_AMOUNT_OVERFLOW");
    assertThat(error.path("traceId").asText()).isNotBlank();
    assertThat(error.path("details").path("currencyCode").asText()).isEqualTo("USD");
    assertThat(sinkingFundTransactionCount(fund.path("id").asText()))
        .isEqualTo(transactionCountBefore);
    assertThat(activeContributionCount(fund.path("id").asText())).isZero();
    assertThat(statusEventCount(entry.path("id").asText())).isEqualTo(statusEventCountBefore);
    assertThat(auditCount("PAYCHECK_ENTRY", UUID.fromString(entry.path("id").asText())))
        .isEqualTo(auditCountBefore);
    assertThat(entryStatus(entry.path("id").asText())).isEqualTo("NOT_PAID");
  }

  @Test
  void withdrawalReversalThatWouldOverflowReturnsStructuredErrorAndRollsBack() throws Exception {
    String token = register("sinking-funds-reversal-overflow@yuuka.local");
    JsonNode fund = createFund(token, "Overflow reversal", null, Long.MAX_VALUE);
    JsonNode withdrawal =
        withdraw(
            token,
            fund.path("id").asText(),
            fund.path("version").asLong(),
            1,
            "2026-07-17",
            "Temporary",
            201);
    insertOpeningBalance(fund.path("id").asText(), 1);

    JsonNode error =
        reverseWithdrawal(
            token,
            withdrawal.path("id").asText(),
            withdrawal.path("version").asLong(),
            "Undo",
            422);

    assertThat(error.path("code").asText()).isEqualTo("MONEY_AMOUNT_OVERFLOW");
    assertThat(error.path("traceId").asText()).isNotBlank();
    assertThat(error.path("details").path("currencyCode").asText()).isEqualTo("USD");
    assertThat(transactionReversedAt(withdrawal.path("id").asText())).isNull();
  }

  @Test
  void batchedListBalanceOverflowIsStructuredAndOwnerScoped() throws Exception {
    String ownerToken = register("sinking-funds-list-overflow@yuuka.local");
    String otherToken = register("sinking-funds-list-overflow-other@yuuka.local");
    JsonNode fund = createFund(ownerToken, "Huge", null, Long.MAX_VALUE);
    insertOpeningBalance(fund.path("id").asText(), Long.MAX_VALUE);

    mockMvc
        .perform(get("/api/v1/sinking-funds").header("Authorization", bearer(ownerToken)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("MONEY_AMOUNT_OVERFLOW"))
        .andExpect(jsonPath("$.details.currencyCode").value("USD"))
        .andExpect(jsonPath("$.traceId").isNotEmpty());

    mockMvc
        .perform(get("/api/v1/sinking-funds").header("Authorization", bearer(otherToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.totalActiveBalanceMinor").value(0))
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void statusReversalCannotOverdrawSinkingFundContribution() throws Exception {
    String token = register("sinking-funds-status-overdraw@yuuka.local");
    JsonNode fund = createFund(token, "Car", null, null);
    JsonNode entry = postedContribution(token, fund, "Car contribution", 100, 200);
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        100,
        "2026-07-18",
        "Repair",
        201);

    JsonNode error =
        changeStatus(
            token, entry.path("id").asText(), "PROCESSING", entry.path("version").asLong(), 422);

    assertThat(error.path("code").asText())
        .isEqualTo("SINKING_FUND_CONTRIBUTION_REVERSAL_EXCEEDS_BALANCE");
    assertThat(error.path("details").path("amountMinor").asLong()).isEqualTo(100);
    assertThat(entryStatus(entry.path("id").asText())).isEqualTo("POSTED");
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);
  }

  @Test
  void deletingPostedContributionCannotOverdrawSinkingFund() throws Exception {
    String token = register("sinking-funds-delete-overdraw@yuuka.local");
    JsonNode fund = createFund(token, "Home", null, null);
    JsonNode entry = postedContribution(token, fund, "Home contribution", 100, 200);
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        100,
        "2026-07-18",
        "Supplies",
        201);

    mockMvc
        .perform(
            delete("/api/v1/entries/{entryId}", entry.path("id").asText())
                .param("version", String.valueOf(entry.path("version").asLong()))
                .header("Authorization", bearer(token)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SINKING_FUND_CONTRIBUTION_REVERSAL_EXCEEDS_BALANCE"));

    assertThat(entryDeletedAt(entry.path("id").asText())).isNull();
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);
  }

  @Test
  void updatingPostedContributionAmountOrFundCannotOverdrawSinkingFund() throws Exception {
    String token = register("sinking-funds-update-overdraw@yuuka.local");
    JsonNode fund = createFund(token, "Medical", null, null);
    JsonNode otherFund = createFund(token, "Moving", null, null);
    JsonNode entry = postedContribution(token, fund, "Medical contribution", 100, 200);
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        100,
        "2026-07-18",
        "Prescription",
        201);

    updateEntry(
        token,
        entry.path("id").asText(),
        entry.path("version").asLong(),
        "SINKING_FUND",
        "Smaller contribution",
        50,
        fund.path("id").asText(),
        422);
    updateEntry(
        token,
        entry.path("id").asText(),
        entry.path("version").asLong(),
        "SINKING_FUND",
        "Move contribution",
        100,
        otherFund.path("id").asText(),
        422);

    JsonNode paycheck = getPaycheck(token, entry.path("paycheckId").asText());
    JsonNode refreshed = entryById(paycheck, entry.path("id").asText());
    assertThat(refreshed.path("amountMinor").asLong()).isEqualTo(100);
    assertThat(refreshed.path("sinkingFundId").asText()).isEqualTo(fund.path("id").asText());
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);
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
      String token, String name, Number targetMinor, Number openingBalanceMinor) throws Exception {
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

  private JsonNode getPaycheck(String token, String paycheckId) throws Exception {
    return json(
        get("/api/v1/paychecks/{paycheckId}", paycheckId).header("Authorization", bearer(token)),
        200);
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

  private JsonNode updateEntry(
      String token,
      String entryId,
      long version,
      String entryType,
      String name,
      long amountMinor,
      String sinkingFundId,
      int expectedStatus)
      throws Exception {
    String sinkingFundJson = sinkingFundId == null ? "null" : "\"%s\"".formatted(sinkingFundId);
    return json(
        patch("/api/v1/entries/{id}", entryId)
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
                  "targetDate":"2026-12-31",
                  "version":%d
                }
                """
                    .formatted(
                        entryType,
                        objectMapper.writeValueAsString(name),
                        amountMinor,
                        sinkingFundJson,
                        version)),
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

  private JsonNode postedContribution(String token, JsonNode fund, String name, long amountMinor)
      throws Exception {
    return postedContribution(token, fund, name, amountMinor, amountMinor);
  }

  private JsonNode postedContribution(
      String token, JsonNode fund, String name, long amountMinor, long paycheckAmountMinor)
      throws Exception {
    JsonNode paycheck = createPaycheck(token, name, paycheckAmountMinor);
    JsonNode entry =
        addEntry(
            token,
            paycheck.path("id").asText(),
            "SINKING_FUND",
            name,
            amountMinor,
            fund.path("id").asText(),
            201);
    return changeStatus(
        token, entry.path("id").asText(), "POSTED", entry.path("version").asLong(), 200);
  }

  private JsonNode entryById(JsonNode paycheck, String entryId) {
    for (JsonNode entry : paycheck.path("entries")) {
      if (entryId.equals(entry.path("id").asText())) {
        return entry;
      }
    }
    throw new AssertionError("Entry not found: " + entryId);
  }

  private void insertOpeningBalance(String fundId, long amountMinor) {
    UUID fundUuid = UUID.fromString(fundId);
    UUID ownerId =
        jdbcTemplate.queryForObject(
            "select owner_id from sinking_funds where id = ?", UUID.class, fundUuid);
    jdbcTemplate.update(
        """
        insert into sinking_fund_transactions (
            id, owner_id, sinking_fund_id, entry_id, transaction_type, amount_minor,
            effective_date, reason, notes, created_at, updated_at, version
        )
        values (?, ?, ?, null, 'OPENING_BALANCE', ?, current_date, 'Manual test balance', null, now(), now(), 0)
        """,
        UUID.randomUUID(),
        ownerId,
        fundUuid,
        amountMinor);
  }

  private long sinkingFundTransactionCount(String fundId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from sinking_fund_transactions where sinking_fund_id = ?",
        Long.class,
        UUID.fromString(fundId));
  }

  private long statusEventCount(String entryId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from entry_status_events where entry_id = ?",
        Long.class,
        UUID.fromString(entryId));
  }

  private long auditCount(String entityType, UUID entityId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_events where entity_type = ? and entity_id = ?",
        Long.class,
        entityType,
        entityId);
  }

  private String entryStatus(String entryId) {
    return jdbcTemplate.queryForObject(
        "select status from paycheck_entries where id = ?", String.class, UUID.fromString(entryId));
  }

  private java.time.Instant entryDeletedAt(String entryId) {
    return jdbcTemplate.queryForObject(
        "select deleted_at from paycheck_entries where id = ?",
        java.time.Instant.class,
        UUID.fromString(entryId));
  }

  private java.time.Instant transactionReversedAt(String transactionId) {
    return jdbcTemplate.queryForObject(
        "select reversed_at from sinking_fund_transactions where id = ?",
        java.time.Instant.class,
        UUID.fromString(transactionId));
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

  private String nullableNumber(Number value) {
    return value == null ? "null" : value.toString();
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
