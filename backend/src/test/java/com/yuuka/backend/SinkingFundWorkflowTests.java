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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    updateEntry(
        token,
        entry.path("id").asText(),
        entry.path("version").asLong(),
        "SINKING_FUND",
        "Unlink contribution",
        100,
        null,
        422);
    updateEntry(
        token,
        entry.path("id").asText(),
        entry.path("version").asLong(),
        "BILL",
        "Bill instead",
        100,
        null,
        422);

    JsonNode paycheck = getPaycheck(token, entry.path("paycheckId").asText());
    JsonNode refreshed = entryById(paycheck, entry.path("id").asText());
    assertThat(refreshed.path("amountMinor").asLong()).isEqualTo(100);
    assertThat(refreshed.path("sinkingFundId").asText()).isEqualTo(fund.path("id").asText());
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);
  }

  @Test
  void sameFundPostedContributionReplacementUsesFinalBalance() throws Exception {
    assertSameFundReplacement("sinking-funds-replace-80@yuuka.local", 80, 30);
    assertSameFundReplacement("sinking-funds-replace-200@yuuka.local", 200, 150);
    assertSameFundReplacement("sinking-funds-replace-zero@yuuka.local", 50, 0);
  }

  @Test
  void sameFundPostedContributionReplacementRollsBackWhenFinalBalanceWouldBeNegative()
      throws Exception {
    String token = register("sinking-funds-replace-negative@yuuka.local");
    JsonNode fund = createFund(token, "Car", null, null);
    JsonNode entry = postedContribution(token, fund, "Car contribution", 100, 200);
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        50,
        "2026-07-18",
        "Repair",
        201);
    JsonNode beforeFund = getFund(token, fund.path("id").asText());
    long auditCountBefore =
        auditCount("PAYCHECK_ENTRY", UUID.fromString(entry.path("id").asText()));

    JsonNode error =
        updateEntry(
            token,
            entry.path("id").asText(),
            entry.path("version").asLong(),
            "SINKING_FUND",
            "Too small",
            49,
            fund.path("id").asText(),
            422);

    assertThat(error.path("code").asText())
        .isEqualTo("SINKING_FUND_CONTRIBUTION_REVERSAL_EXCEEDS_BALANCE");
    assertThat(error.path("details").path("amountMinor").asLong()).isEqualTo(1);
    JsonNode afterFund = getFund(token, fund.path("id").asText());
    assertThat(afterFund.path("currentBalanceMinor").asLong()).isEqualTo(50);
    assertThat(afterFund.path("version").asLong()).isEqualTo(beforeFund.path("version").asLong());
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);
    assertThat(totalContributionCount(fund.path("id").asText())).isEqualTo(1);
    JsonNode refreshed =
        entryById(getPaycheck(token, entry.path("paycheckId").asText()), entry.path("id").asText());
    assertThat(refreshed.path("amountMinor").asLong()).isEqualTo(100);
    assertThat(refreshed.path("version").asLong()).isEqualTo(entry.path("version").asLong());
    assertThat(auditCount("PAYCHECK_ENTRY", UUID.fromString(entry.path("id").asText())))
        .isEqualTo(auditCountBefore);
  }

  @Test
  void concurrentOppositeFundReassignmentsUseStableFundLockOrder() throws Exception {
    String token = register("sinking-funds-opposite-reassign@yuuka.local");
    JsonNode firstFund = createFund(token, "First", null, null);
    JsonNode secondFund = createFund(token, "Second", null, null);
    JsonNode firstEntry = postedContribution(token, firstFund, "First contribution", 100, 300);
    JsonNode secondEntry = postedContribution(token, secondFund, "Second contribution", 100, 300);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<JsonNode> firstMove =
          executor.submit(
              () -> {
                await(start);
                return updateEntry(
                    token,
                    firstEntry.path("id").asText(),
                    firstEntry.path("version").asLong(),
                    "SINKING_FUND",
                    "First moved",
                    100,
                    secondFund.path("id").asText(),
                    200);
              });
      Future<JsonNode> secondMove =
          executor.submit(
              () -> {
                await(start);
                return updateEntry(
                    token,
                    secondEntry.path("id").asText(),
                    secondEntry.path("version").asLong(),
                    "SINKING_FUND",
                    "Second moved",
                    100,
                    firstFund.path("id").asText(),
                    200);
              });
      start.countDown();

      JsonNode firstUpdated = firstMove.get(10, TimeUnit.SECONDS);
      JsonNode secondUpdated = secondMove.get(10, TimeUnit.SECONDS);

      assertThat(firstUpdated.path("sinkingFundId").asText())
          .isEqualTo(secondFund.path("id").asText());
      assertThat(secondUpdated.path("sinkingFundId").asText())
          .isEqualTo(firstFund.path("id").asText());
      assertThat(getFund(token, firstFund.path("id").asText()).path("currentBalanceMinor").asLong())
          .isEqualTo(100);
      assertThat(
              getFund(token, secondFund.path("id").asText()).path("currentBalanceMinor").asLong())
          .isEqualTo(100);
      assertThat(activeContributionCount(firstFund.path("id").asText())).isEqualTo(1);
      assertThat(activeContributionCount(secondFund.path("id").asText())).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void paybackAndPersistentSinkingFundAssignmentsAreExclusive() throws Exception {
    String token = register("sinking-funds-exclusive@yuuka.local");
    JsonNode fund = createFund(token, "Fund", null, null);
    JsonNode payback = createPayback(token, "Loan", 500);
    JsonNode paycheck = createPaycheck(token, "Assignments", 500);
    long entryCountBefore = tableCount("paycheck_entries");

    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/entries", paycheck.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"SINKING_FUND",
                      "name":"Both",
                      "amountMinor":100,
                      "paybackId":"%s",
                      "sinkingFundId":"%s"
                    }
                    """
                        .formatted(payback.path("id").asText(), fund.path("id").asText())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ENTRY_MULTIPLE_BALANCE_ASSIGNMENTS"));
    assertThat(tableCount("paycheck_entries")).isEqualTo(entryCountBefore);

    JsonNode paybackEntry =
        addEntryWithAssignments(
            token,
            paycheck.path("id").asText(),
            "SINKING_FUND",
            "Payback first",
            100,
            payback.path("id").asText(),
            null,
            201);
    changeStatus(
        token,
        paybackEntry.path("id").asText(),
        "POSTED",
        paybackEntry.path("version").asLong(),
        200);
    JsonNode switchedToFund =
        updateEntryWithAssignments(
            token,
            paybackEntry.path("id").asText(),
            1,
            "SINKING_FUND",
            "Fund now",
            100,
            null,
            fund.path("id").asText(),
            200);
    assertThat(switchedToFund.path("paybackId").isNull()).isTrue();
    assertThat(switchedToFund.path("sinkingFundId").asText()).isEqualTo(fund.path("id").asText());
    assertThat(activeRepaymentCount(payback.path("id").asText())).isZero();
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);

    JsonNode rejectedBoth =
        updateEntryWithAssignments(
            token,
            paybackEntry.path("id").asText(),
            switchedToFund.path("version").asLong(),
            "SINKING_FUND",
            "Both raw",
            100,
            payback.path("id").asText(),
            fund.path("id").asText(),
            422);
    assertThat(rejectedBoth.path("code").asText()).isEqualTo("ENTRY_MULTIPLE_BALANCE_ASSIGNMENTS");
    assertThat(activeRepaymentCount(payback.path("id").asText())).isZero();
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);

    JsonNode switchedToPayback =
        updateEntryWithAssignments(
            token,
            paybackEntry.path("id").asText(),
            switchedToFund.path("version").asLong(),
            "SINKING_FUND",
            "Payback now",
            100,
            payback.path("id").asText(),
            null,
            200);
    assertThat(switchedToPayback.path("paybackId").asText()).isEqualTo(payback.path("id").asText());
    assertThat(switchedToPayback.path("sinkingFundId").isNull()).isTrue();
    assertThat(activeRepaymentCount(payback.path("id").asText())).isEqualTo(1);
    assertThat(activeContributionCount(fund.path("id").asText())).isZero();
  }

  @Test
  void draftCreationWithMultipleBalanceAssignmentsRollsBackEverything() throws Exception {
    String token = register("sinking-funds-draft-exclusive@yuuka.local");
    JsonNode fund = createFund(token, "Draft fund", null, null);
    JsonNode payback = createPayback(token, "Draft loan", 500);
    List<String> tables =
        List.of("paychecks", "paycheck_entries", "entry_status_events", "audit_events");
    MapSnapshot before = snapshot(tables);

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-draft")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Bad draft",
                      "amountMinor":100,
                      "incomeDate":"2026-07-17",
                      "entries":[
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Both",
                          "amountMinor":100,
                          "paybackId":"%s",
                          "sinkingFundId":"%s"
                        }
                      ]
                    }
                    """
                        .formatted(payback.path("id").asText(), fund.path("id").asText())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ENTRY_MULTIPLE_BALANCE_ASSIGNMENTS"));

    assertSnapshot(before);
  }

  private void assertSameFundReplacement(String email, long replacementAmount, long expectedBalance)
      throws Exception {
    String token = register(email);
    JsonNode fund = createFund(token, "Replacement", null, null);
    JsonNode entry = postedContribution(token, fund, "Replacement contribution", 100, 200);
    withdraw(
        token,
        fund.path("id").asText(),
        getFund(token, fund.path("id").asText()).path("version").asLong(),
        50,
        "2026-07-18",
        "Partial use",
        201);

    JsonNode updated =
        updateEntry(
            token,
            entry.path("id").asText(),
            entry.path("version").asLong(),
            "SINKING_FUND",
            "Replacement contribution",
            replacementAmount,
            fund.path("id").asText(),
            200);

    assertThat(updated.path("amountMinor").asLong()).isEqualTo(replacementAmount);
    assertThat(getFund(token, fund.path("id").asText()).path("currentBalanceMinor").asLong())
        .isEqualTo(expectedBalance);
    assertThat(activeContributionCount(fund.path("id").asText())).isEqualTo(1);
    assertThat(totalContributionCount(fund.path("id").asText())).isEqualTo(2);
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

  private JsonNode createPayback(String token, String name, long openingRemainingMinor)
      throws Exception {
    return json(
        post("/api/v1/paybacks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "name":%s,
                  "originalAmountMinor":%d,
                  "openingRemainingAmountMinor":%d,
                  "borrowedDate":"2026-07-01",
                  "notes":null
                }
                """
                    .formatted(
                        objectMapper.writeValueAsString(name),
                        openingRemainingMinor,
                        openingRemainingMinor)),
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

  private JsonNode addEntryWithAssignments(
      String token,
      String paycheckId,
      String entryType,
      String name,
      long amountMinor,
      String paybackId,
      String sinkingFundId,
      int expectedStatus)
      throws Exception {
    String paybackJson = paybackId == null ? "null" : "\"%s\"".formatted(paybackId);
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
                  "paybackId":%s,
                  "sinkingFundId":%s
                }
                """
                    .formatted(
                        entryType,
                        objectMapper.writeValueAsString(name),
                        amountMinor,
                        paybackJson,
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

  private JsonNode updateEntryWithAssignments(
      String token,
      String entryId,
      long version,
      String entryType,
      String name,
      long amountMinor,
      String paybackId,
      String sinkingFundId,
      int expectedStatus)
      throws Exception {
    String paybackJson = paybackId == null ? "null" : "\"%s\"".formatted(paybackId);
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
                  "paybackId":%s,
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
                        paybackJson,
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

  private long activeRepaymentCount(String paybackId) {
    return jdbcTemplate.queryForObject(
        """
        select count(*) from payback_repayments
        where payback_id = ? and reversed_at is null
        """,
        Long.class,
        UUID.fromString(paybackId));
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

  private long tableCount(String tableName) {
    return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
  }

  private MapSnapshot snapshot(List<String> tableNames) {
    return new MapSnapshot(
        tableNames.stream()
            .collect(java.util.stream.Collectors.toMap(name -> name, this::tableCount)));
  }

  private void assertSnapshot(MapSnapshot expected) {
    expected
        .counts()
        .forEach((tableName, count) -> assertThat(tableCount(tableName)).isEqualTo(count));
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for concurrent workflow");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted waiting for concurrent workflow", exception);
    }
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

  private record MapSnapshot(Map<String, Long> counts) {}
}
