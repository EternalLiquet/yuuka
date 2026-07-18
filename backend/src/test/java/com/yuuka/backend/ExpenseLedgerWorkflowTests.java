package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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

@AutoConfigureMockMvc
class ExpenseLedgerWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void managesOpenLedgerItemsAndDerivesTotalsFromLiveExpenses() throws Exception {
    String token = registerAndGetAccessToken("expense-ledger-crud@yuuka.local");
    JsonNode ledger = createLedger(token, "Road trip");

    JsonNode gas =
        createItem(token, ledger.path("id").asText(), "Gas", "Shell", 4500, "2026-07-16");
    JsonNode snacks =
        createItem(token, ledger.path("id").asText(), null, "Mart", 1250, "2026-07-17");
    updateItem(token, gas.path("id").asText(), gas.path("version").asLong(), "Gas", "Shell", 5000);
    deleteItem(token, snacks.path("id").asText(), snacks.path("version").asLong());

    mockMvc
        .perform(
            get("/api/v1/expense-ledgers/{id}", ledger.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalMinor").value(5000))
        .andExpect(jsonPath("$.itemCount").value(1))
        .andExpect(jsonPath("$.latestExpenseDate").value("2026-07-17"))
        .andExpect(jsonPath("$.items.length()").value(1));

    mockMvc
        .perform(get("/api/v1/expense-ledgers").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].totalMinor").value(5000))
        .andExpect(jsonPath("$.items[0].items.length()").value(0));

    assertThat(
            auditCount("EXPENSE_LEDGER_ITEM", UUID.fromString(gas.path("id").asText()), "UPDATED"))
        .isEqualTo(1);
  }

  @Test
  void finalizesReopensAndRejectsMutableOperationsWhileFinalized() throws Exception {
    String token = registerAndGetAccessToken("expense-ledger-lifecycle@yuuka.local");
    JsonNode ledger = createLedger(token, "Supplies");

    finalizeLedger(token, ledger.path("id").asText(), ledger.path("version").asLong(), 422);
    JsonNode item = createItem(token, ledger.path("id").asText(), "Tape", null, 799, "2026-07-17");
    JsonNode finalized =
        finalizeLedger(
            token, ledger.path("id").asText(), refreshedLedgerVersion(token, ledger), 200);

    createItemExpecting(token, ledger.path("id").asText(), "Box", null, 1200, 422);
    updateLedger(token, ledger.path("id").asText(), finalized.path("version").asLong(), 422);

    JsonNode reopened =
        reopenLedger(token, ledger.path("id").asText(), finalized.path("version").asLong(), 200);
    JsonNode updated =
        updateItem(
            token,
            item.path("id").asText(),
            refreshedItemVersion(token, ledger, item),
            "Tape",
            null,
            899);
    assertThat(updated.path("amountMinor").asLong()).isEqualTo(899);
    assertThat(reopened.path("state").asText()).isEqualTo("OPEN");
  }

  @Test
  void preventsOverflowAndRollsBackRejectedItemWrites() throws Exception {
    String token = registerAndGetAccessToken("expense-ledger-overflow@yuuka.local");
    JsonNode createLedger = createLedger(token, "Exact max create");
    JsonNode maxItem =
        createItem(
            token, createLedger.path("id").asText(), "Maximum", null, Long.MAX_VALUE, "2026-07-17");
    JsonNode exactMax = getLedger(token, createLedger.path("id").asText());
    long exactMaxVersion = exactMax.path("version").asLong();
    int createdAuditsBefore = createdItemAuditCount(createLedger.path("id").asText());

    JsonNode createOverflow =
        createItemExpecting(
            token, createLedger.path("id").asText(), "Overflow", null, 1, "2026-07-17", 422);

    assertOverflow(createOverflow);
    JsonNode afterCreateRejection = getLedger(token, createLedger.path("id").asText());
    assertThat(afterCreateRejection.path("totalMinor").asLong()).isEqualTo(Long.MAX_VALUE);
    assertThat(afterCreateRejection.path("itemCount").asLong()).isEqualTo(1);
    assertThat(afterCreateRejection.path("version").asLong()).isEqualTo(exactMaxVersion);
    assertThat(createdItemAuditCount(createLedger.path("id").asText()))
        .isEqualTo(createdAuditsBefore);

    JsonNode updateLedger = createLedger(token, "Exact max update");
    createItem(
        token, updateLedger.path("id").asText(), "Large", null, Long.MAX_VALUE - 1, "2026-07-17");
    JsonNode one =
        createItem(token, updateLedger.path("id").asText(), "One", null, 1, "2026-07-17");
    JsonNode beforeUpdateRejection = getLedger(token, updateLedger.path("id").asText());
    JsonNode updateOverflow =
        updateItemExpecting(
            token, one.path("id").asText(), one.path("version").asLong(), "Two", null, 2, 422);

    assertOverflow(updateOverflow);
    JsonNode afterUpdateRejection = getLedger(token, updateLedger.path("id").asText());
    assertThat(afterUpdateRejection.path("totalMinor").asLong()).isEqualTo(Long.MAX_VALUE);
    assertThat(afterUpdateRejection.path("version").asLong())
        .isEqualTo(beforeUpdateRejection.path("version").asLong());
    assertThat(refreshedItem(token, updateLedger, one).path("amountMinor").asLong()).isEqualTo(1);
    assertThat(
            auditCount("EXPENSE_LEDGER_ITEM", UUID.fromString(one.path("id").asText()), "UPDATED"))
        .isZero();

    deleteItem(token, maxItem.path("id").asText(), maxItem.path("version").asLong());
    JsonNode afterExactMaxDelete = getLedger(token, createLedger.path("id").asText());
    assertThat(afterExactMaxDelete.path("totalMinor").asLong()).isZero();
    assertThat(afterExactMaxDelete.path("itemCount").asLong()).isZero();
  }

  @Test
  void serializesConcurrentItemAddsBeforeValidatingTheProspectiveTotal() throws Exception {
    String token = registerAndGetAccessToken("expense-ledger-concurrent-overflow@yuuka.local");
    JsonNode ledger = createLedger(token, "Concurrent max");
    String ledgerId = ledger.path("id").asText();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ItemWriteResult> maximum =
          executor.submit(
              () -> concurrentCreate(token, ledgerId, "Maximum", Long.MAX_VALUE, ready, start));
      Future<ItemWriteResult> one =
          executor.submit(() -> concurrentCreate(token, ledgerId, "One", 1, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<ItemWriteResult> results =
          List.of(maximum.get(30, TimeUnit.SECONDS), one.get(30, TimeUnit.SECONDS));
      assertThat(results).extracting(ItemWriteResult::status).containsExactlyInAnyOrder(201, 422);
      JsonNode overflow =
          results.stream()
              .filter(result -> result.status() == 422)
              .map(ItemWriteResult::body)
              .findFirst()
              .orElseThrow();
      assertOverflow(overflow);

      JsonNode persisted = getLedger(token, ledgerId);
      assertThat(persisted.path("itemCount").asLong()).isEqualTo(1);
      assertThat(persisted.path("totalMinor").canConvertToLong()).isTrue();
      assertThat(createdItemAuditCount(ledgerId)).isEqualTo(1);
      assertThat(liveItemCount(ledgerId)).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void settlesFinalizedLedgerAsBillWithServerDerivedAmountAndProvenance() throws Exception {
    String token = registerAndGetAccessToken("expense-ledger-bill@yuuka.local");
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode ledger = createLedger(token, "Card cleanup");
    createItem(token, ledger.path("id").asText(), "Groceries", "Store", 2500, "2026-07-15");
    createItem(token, ledger.path("id").asText(), "Gas", "Station", 3000, "2026-07-17");
    JsonNode finalized =
        finalizeLedger(
            token, ledger.path("id").asText(), refreshedLedgerVersion(token, ledger), 200);

    JsonNode result =
        settleAsBill(
            token,
            ledger.path("id").asText(),
            paycheck.path("id").asText(),
            finalized.path("version").asLong(),
            200);

    assertThat(result.path("ledger").path("state").asText()).isEqualTo("SETTLED");
    assertThat(result.path("billEntry").path("amountMinor").asLong()).isEqualTo(5500);
    assertThat(result.path("billEntry").path("status").asText()).isEqualTo("NOT_PAID");
    assertThat(result.path("billEntry").path("paymentMethod").asText()).isEqualTo("AUTOPAY");
    assertThat(result.path("billEntry").path("sourceExpenseLedgerId").asText())
        .isEqualTo(ledger.path("id").asText());
    JsonNode settlement = result.path("ledger").path("settlement");
    assertThat(settlement.path("targetId").asText())
        .isEqualTo(result.path("billEntry").path("id").asText());
    assertThat(settlement.path("targetPaycheckId").asText())
        .isEqualTo(paycheck.path("id").asText());

    JsonNode reloaded = getLedger(token, ledger.path("id").asText());
    assertThat(reloaded.path("settlement").path("targetId").asText())
        .isEqualTo(result.path("billEntry").path("id").asText());
    assertThat(reloaded.path("settlement").path("targetPaycheckId").asText())
        .isEqualTo(paycheck.path("id").asText());

    deleteEntry(
        token,
        result.path("billEntry").path("id").asText(),
        result.path("billEntry").path("version").asLong());
    JsonNode afterTargetDelete = getLedger(token, ledger.path("id").asText());
    assertThat(afterTargetDelete.path("settlement").path("targetId").asText())
        .isEqualTo(result.path("billEntry").path("id").asText());
    assertThat(afterTargetDelete.path("settlement").path("targetPaycheckId").asText())
        .isEqualTo(paycheck.path("id").asText());

    settleAsBill(
        token,
        ledger.path("id").asText(),
        paycheck.path("id").asText(),
        result.path("ledger").path("version").asLong(),
        422);
    reopenLedger(
        token, ledger.path("id").asText(), result.path("ledger").path("version").asLong(), 422);
    assertThat(settlementCount(UUID.fromString(ledger.path("id").asText()))).isEqualTo(1);
  }

  @Test
  void settlesFinalizedLedgerAsPaybackAndKeepsSourceAndTargetIndependent() throws Exception {
    String token = registerAndGetAccessToken("expense-ledger-payback@yuuka.local");
    JsonNode ledger = createLedger(token, "Emergency cash");
    createItem(token, ledger.path("id").asText(), "Tow", "Garage", 9000, "2026-07-10");
    createItem(token, ledger.path("id").asText(), "Taxi", null, 2000, "2026-07-12");
    JsonNode finalized =
        finalizeLedger(
            token, ledger.path("id").asText(), refreshedLedgerVersion(token, ledger), 200);

    JsonNode result =
        settleAsPayback(token, ledger.path("id").asText(), finalized.path("version").asLong(), 200);

    JsonNode payback = result.path("payback");
    assertThat(payback.path("name").asText()).isEqualTo("Emergency cash");
    assertThat(payback.path("originalAmountMinor").asLong()).isEqualTo(11000);
    assertThat(payback.path("openingRemainingAmountMinor").asLong()).isEqualTo(11000);
    assertThat(payback.path("borrowedDate").asText()).isEqualTo("2026-07-12");
    assertThat(payback.path("sourceExpenseLedgerId").asText())
        .isEqualTo(ledger.path("id").asText());

    deletePayback(token, payback.path("id").asText(), payback.path("version").asLong());
    mockMvc
        .perform(
            get("/api/v1/expense-ledgers/{id}", ledger.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SETTLED"))
        .andExpect(jsonPath("$.settlement.targetId").value(payback.path("id").asText()))
        .andExpect(jsonPath("$.settlement.targetPaycheckId").value(nullValue()));
  }

  @Test
  void rejectsFutureDatesCrossOwnerAccessAndSettlementRollbackOnTargetFailure() throws Exception {
    String ownerToken = registerAndGetAccessToken("expense-ledger-owner@yuuka.local");
    String otherToken = registerAndGetAccessToken("expense-ledger-other@yuuka.local");
    JsonNode ledger = createLedger(ownerToken, "Private");

    createItemExpectingFutureDate(
        ownerToken, ledger.path("id").asText(), "Future", null, 1000, 422);
    mockMvc
        .perform(
            get("/api/v1/expense-ledgers/{id}", ledger.path("id").asText())
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    createItem(ownerToken, ledger.path("id").asText(), "Allowed", null, 6000, "2026-07-17");
    JsonNode finalized =
        finalizeLedger(
            ownerToken,
            ledger.path("id").asText(),
            refreshedLedgerVersion(ownerToken, ledger),
            200);
    JsonNode tinyPaycheck = createPaycheck(ownerToken, 1000);
    settleAsBill(
        ownerToken,
        ledger.path("id").asText(),
        tinyPaycheck.path("id").asText(),
        finalized.path("version").asLong(),
        422);

    mockMvc
        .perform(
            get("/api/v1/expense-ledgers/{id}", ledger.path("id").asText())
                .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("FINALIZED"))
        .andExpect(jsonPath("$.settlement").value(nullValue()));
  }

  private JsonNode createLedger(String token, String name) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                post("/api/v1/expense-ledgers")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void updateLedger(String token, String ledgerId, long version, int statusCode)
      throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/expense-ledgers/{id}", ledgerId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Changed\",\"version\":" + version + "}"))
        .andExpect(status().is(statusCode));
  }

  private JsonNode createItem(
      String token,
      String ledgerId,
      String name,
      String merchant,
      long amountMinor,
      String expenseDate)
      throws Exception {
    return createItemExpecting(token, ledgerId, name, merchant, amountMinor, expenseDate, 201);
  }

  private void createItemExpecting(
      String token, String ledgerId, String name, String merchant, long amountMinor, int statusCode)
      throws Exception {
    createItemExpecting(token, ledgerId, name, merchant, amountMinor, "2026-07-18", statusCode);
  }

  private JsonNode createItemExpectingFutureDate(
      String token, String ledgerId, String name, String merchant, long amountMinor, int statusCode)
      throws Exception {
    return createItemExpecting(
        token, ledgerId, name, merchant, amountMinor, "2099-01-01", statusCode);
  }

  private JsonNode createItemExpecting(
      String token,
      String ledgerId,
      String name,
      String merchant,
      long amountMinor,
      String expenseDate,
      int statusCode)
      throws Exception {
    String content =
        """
        {
          "name": %s,
          "merchant": %s,
          "amountMinor": %d,
          "expenseDate": "%s"
        }
        """
            .formatted(jsonString(name), jsonString(merchant), amountMinor, expenseDate);
    String response =
        mockMvc
            .perform(
                post("/api/v1/expense-ledgers/{id}/items", ledgerId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andExpect(status().is(statusCode))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return response.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response);
  }

  private JsonNode updateItem(
      String token, String itemId, long version, String name, String merchant, long amountMinor)
      throws Exception {
    return updateItemExpecting(token, itemId, version, name, merchant, amountMinor, 200);
  }

  private JsonNode updateItemExpecting(
      String token,
      String itemId,
      long version,
      String name,
      String merchant,
      long amountMinor,
      int statusCode)
      throws Exception {
    String content =
        """
        {
          "name": %s,
          "merchant": %s,
          "amountMinor": %d,
          "expenseDate": "2026-07-17",
          "version": %d
        }
        """
            .formatted(jsonString(name), jsonString(merchant), amountMinor, version);
    return objectMapper.readTree(
        mockMvc
            .perform(
                patch("/api/v1/expense-ledgers/items/{id}", itemId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andExpect(status().is(statusCode))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void deleteItem(String token, String itemId, long version) throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/expense-ledgers/items/{id}", itemId)
                .queryParam("version", Long.toString(version))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
  }

  private void deleteEntry(String token, String entryId, long version) throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/entries/{id}", entryId)
                .queryParam("version", Long.toString(version))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
  }

  private JsonNode finalizeLedger(String token, String ledgerId, long version, int statusCode)
      throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                post("/api/v1/expense-ledgers/{id}/finalize", ledgerId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version + "}"))
            .andExpect(status().is(statusCode))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode reopenLedger(String token, String ledgerId, long version, int statusCode)
      throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                post("/api/v1/expense-ledgers/{id}/reopen", ledgerId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version + "}"))
            .andExpect(status().is(statusCode))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode settleAsBill(
      String token, String ledgerId, String paycheckId, long ledgerVersion, int statusCode)
      throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                post("/api/v1/expense-ledgers/{id}/settle/bill", ledgerId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"paycheckId":"%s","ledgerVersion":%d}
                        """
                            .formatted(paycheckId, ledgerVersion)))
            .andExpect(status().is(statusCode))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode settleAsPayback(
      String token, String ledgerId, long ledgerVersion, int statusCode) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                post("/api/v1/expense-ledgers/{id}/settle/payback", ledgerId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ledgerVersion\":" + ledgerVersion + "}"))
            .andExpect(status().is(statusCode))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode createPaycheck(String token, long amountMinor) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Settlement Target","amountMinor":%d,"incomeDate":"2026-07-17"}
                        """
                            .formatted(amountMinor)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void deletePayback(String token, String paybackId, long version) throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/paybacks/{id}", paybackId)
                .queryParam("version", Long.toString(version))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
  }

  private long refreshedLedgerVersion(String token, JsonNode ledger) throws Exception {
    return getLedger(token, ledger.path("id").asText()).path("version").asLong();
  }

  private long refreshedItemVersion(String token, JsonNode ledger, JsonNode item) throws Exception {
    return refreshedItem(token, ledger, item).path("version").asLong();
  }

  private JsonNode refreshedItem(String token, JsonNode ledger, JsonNode item) throws Exception {
    for (JsonNode candidate : getLedger(token, ledger.path("id").asText()).path("items")) {
      if (candidate.path("id").asText().equals(item.path("id").asText())) {
        return candidate;
      }
    }
    throw new AssertionError("Missing item");
  }

  private JsonNode getLedger(String token, String ledgerId) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                get("/api/v1/expense-ledgers/{id}", ledgerId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private ItemWriteResult concurrentCreate(
      String token,
      String ledgerId,
      String name,
      long amountMinor,
      CountDownLatch ready,
      CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("Concurrent item additions did not start together");
    }
    var result =
        mockMvc
            .perform(
                post("/api/v1/expense-ledgers/{id}/items", ledgerId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "%s",
                          "amountMinor": %d,
                          "expenseDate": "2026-07-17"
                        }
                        """
                            .formatted(name, amountMinor)))
            .andReturn()
            .getResponse();
    String content = result.getContentAsString();
    return new ItemWriteResult(
        result.getStatus(),
        content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content));
  }

  private void assertOverflow(JsonNode response) {
    assertThat(response.path("code").asText()).isEqualTo("MONEY_AMOUNT_OVERFLOW");
    assertThat(response.path("details").path("currencyCode").asText()).isEqualTo("USD");
    assertThat(response.path("traceId").asText()).isNotBlank();
  }

  private int createdItemAuditCount(String ledgerId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where entity_type = 'EXPENSE_LEDGER_ITEM' "
                + "and action = 'CREATED' and metadata ->> 'ledgerId' = ?",
            Integer.class,
            ledgerId);
    return count == null ? 0 : count;
  }

  private int liveItemCount(String ledgerId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from expense_ledger_items where ledger_id = ? and deleted_at is null",
            Integer.class,
            UUID.fromString(ledgerId));
    return count == null ? 0 : count;
  }

  private int auditCount(String entityType, UUID entityId, String action) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where entity_type = ? and entity_id = ? and action = ?",
            Integer.class,
            entityType,
            entityId,
            action);
    return count == null ? 0 : count;
  }

  private int settlementCount(UUID ledgerId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from expense_ledger_settlements where ledger_id = ?",
            Integer.class,
            ledgerId);
    return count == null ? 0 : count;
  }

  private String registerAndGetAccessToken(String email) throws Exception {
    String response =
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
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).path("accessToken").asText();
  }

  private String jsonString(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private record ItemWriteResult(int status, JsonNode body) {}
}
