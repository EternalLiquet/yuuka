package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportItemRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillImportRequest;
import com.yuuka.backend.recurring.api.dto.RecurringBillResponse;
import com.yuuka.backend.recurring.api.dto.UpdateRecurringBillRequest;
import com.yuuka.backend.recurring.application.RecurringBillService;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class RecurringBillWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RecurringBillService recurringBillService;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  void managesOwnerScopedDefinitionsWithSearchLifecycleAndOptimisticLocking() throws Exception {
    String ownerToken = register("recurring-owner@yuuka.local");
    String otherToken = register("recurring-other@yuuka.local");
    JsonNode created = createDefinition(ownerToken, "Electric", 12000, 31, "AUTOPAY");

    mockMvc
        .perform(
            get("/api/v1/recurring-bills/{id}", created.path("id").asText())
                .header("Authorization", bearer(ownerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recurrenceType").value("MONTHLY"))
        .andExpect(jsonPath("$.active").value(true));
    mockMvc
        .perform(
            get("/api/v1/recurring-bills/{id}", created.path("id").asText())
                .header("Authorization", bearer(otherToken)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/v1/recurring-bills?status=ALL&search=utility")
                .header("Authorization", bearer(ownerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(created.path("id").asText()));

    JsonNode updated =
        requestJson(
            put("/api/v1/recurring-bills/{id}", created.path("id").asText())
                .header("Authorization", bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Electric updated",
                      "typicalAmountMinor":14600,
                      "paymentMethod":"MANUAL",
                      "dueDay":30,
                      "accountName":"Utility account",
                      "payee":"Power Co",
                      "version":%d
                    }
                    """
                        .formatted(created.path("version").asLong())),
            200);
    assertThat(updated.path("typicalAmountMinor").asLong()).isEqualTo(14600);
    assertThat(updated.path("paymentMethod").asText()).isEqualTo("MANUAL");

    mockMvc
        .perform(
            post("/api/v1/recurring-bills/{id}/deactivate", created.path("id").asText())
                .header("Authorization", bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":%d}".formatted(updated.path("version").asLong() + 1)))
        .andExpect(status().isConflict());
    JsonNode inactive =
        requestJson(
            post("/api/v1/recurring-bills/{id}/deactivate", created.path("id").asText())
                .header("Authorization", bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":%d}".formatted(updated.path("version").asLong())),
            200);
    mockMvc
        .perform(get("/api/v1/recurring-bills").header("Authorization", bearer(ownerToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    mockMvc
        .perform(
            post("/api/v1/recurring-bills/{id}/activate", created.path("id").asText())
                .header("Authorization", bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":%d}".formatted(inactive.path("version").asLong())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void generatesClampedInclusiveTimelineWithDeterministicOrdering() throws Exception {
    String token = register("recurring-timeline@yuuka.local");
    JsonNode zebra = createDefinition(token, "Zebra", 1000, 31, "AUTOPAY");
    createDefinition(token, "alpha", 2000, 31, "MANUAL");
    JsonNode hidden = createDefinition(token, "Hidden", 3000, 28, "AUTOPAY");
    requestJson(
        post("/api/v1/recurring-bills/{id}/deactivate", hidden.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":%d}".formatted(hidden.path("version").asLong())),
        200);

    mockMvc
        .perform(
            get("/api/v1/recurring-bills/timeline?from=2028-02-29&through=2028-04-30")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(6))
        .andExpect(jsonPath("$.items[0].occurrenceDate").value("2028-02-29"))
        .andExpect(jsonPath("$.items[0].name").value("alpha"))
        .andExpect(jsonPath("$.items[1].name").value("Zebra"))
        .andExpect(jsonPath("$.items[4].occurrenceDate").value("2028-04-30"))
        .andExpect(jsonPath("$.items[0].definitionVersion").isNumber());
    mockMvc
        .perform(
            get("/api/v1/recurring-bills/timeline?from=2026-01-01&through=2027-12-31")
                .header("Authorization", bearer(token)))
        .andExpect(status().isUnprocessableEntity());
    assertThat(zebra.path("dueDay").asInt()).isEqualTo(31);
  }

  @Test
  void importsIndependentSnapshotsAllowsDuplicatesAndReportsTimelineStatus() throws Exception {
    String token = register("recurring-import@yuuka.local");
    JsonNode definition = createDefinition(token, "Rent", 94000, 31, "MANUAL");
    JsonNode paycheck = createPaycheck(token, "Utilities 1/2", 250000, "2026-02-20");

    JsonNode imported = importBills(token, paycheck, definition, "2026-02-28", 95000, true, 200);
    assertThat(imported.path("entries").size()).isEqualTo(1);
    JsonNode first = imported.path("entries").get(0);
    assertThat(first.path("status").asText()).isEqualTo("NOT_PAID");
    assertThat(first.path("paymentMethod").asText()).isEqualTo("MANUAL");
    assertThat(first.path("dueDate").asText()).isEqualTo("2026-02-28");
    assertThat(first.path("sourceRecurringBillDefinitionId").asText())
        .isEqualTo(definition.path("id").asText());

    importBills(token, imported, definition, "2026-02-28", 94000, false, 409);
    JsonNode refreshedDefinition = getDefinition(token, definition.path("id").asText());
    assertThat(refreshedDefinition.path("typicalAmountMinor").asLong()).isEqualTo(95000);
    JsonNode duplicated =
        importBills(token, imported, refreshedDefinition, "2026-02-28", 94000, false, 200);
    assertThat(duplicated.path("entries").size()).isEqualTo(2);

    mockMvc
        .perform(
            get("/api/v1/recurring-bills/timeline?from=2026-02-28&through=2026-02-28")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].importCount").value(2))
        .andExpect(jsonPath("$.items[0].imports[0].paycheckName").value("Utilities 1/2"))
        .andExpect(jsonPath("$.items[0].imports[0].status").value("NOT_PAID"));

    mockMvc
        .perform(
            delete(
                    "/api/v1/recurring-bills/{id}?version={version}",
                    definition.path("id").asText(),
                    refreshedDefinition.path("version").asLong())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", paycheck.path("id").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(2))
        .andExpect(jsonPath("$.entries[0].name").value("Rent"));
  }

  @Test
  void rollsBackTypicalAmountUpdateWhenImportWouldOverAllocate() throws Exception {
    String token = register("recurring-rollback@yuuka.local");
    JsonNode definition = createDefinition(token, "Large bill", 10000, 21, "AUTOPAY");
    JsonNode paycheck = createPaycheck(token, "Small", 5000, "2026-07-14");

    importBills(token, paycheck, definition, "2026-07-21", 6000, true, 422);

    assertThat(
            getDefinition(token, definition.path("id").asText())
                .path("typicalAmountMinor")
                .asLong())
        .isEqualTo(10000);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from paycheck_entries where paycheck_id = ?",
                Long.class,
                UUID.fromString(paycheck.path("id").asText())))
        .isZero();
  }

  @Test
  void validatesTypicalAmountUpdatesForTheWholeImportBeforeWriting() throws Exception {
    String token = register("recurring-typical-batch@yuuka.local");
    JsonNode first = createDefinition(token, "First", 1000, 31, "AUTOPAY");
    JsonNode second = createDefinition(token, "Second", 2000, 28, "MANUAL");

    JsonNode duplicateOccurrencesPaycheck =
        createPaycheck(token, "Duplicate occurrences", 10000, "2026-01-15");
    JsonNode duplicateOccurrences =
        importBatch(
            token,
            duplicateOccurrencesPaycheck,
            List.of(
                importItem(first, "2026-01-31", 1100, false),
                importItem(first, "2026-02-28", 1200, false)),
            200);
    assertThat(duplicateOccurrences.path("entries").size()).isEqualTo(2);

    JsonNode oneUpdatePaycheck = createPaycheck(token, "One update", 10000, "2026-01-15");
    importBatch(
        token,
        oneUpdatePaycheck,
        List.of(
            importItem(first, "2026-01-31", 1300, true),
            importItem(first, "2026-02-28", 1200, false)),
        200);
    JsonNode refreshedFirst = getDefinition(token, first.path("id").asText());
    assertThat(refreshedFirst.path("typicalAmountMinor").asLong()).isEqualTo(1300);

    JsonNode rejectedPaycheck = createPaycheck(token, "Rejected updates", 10000, "2026-01-15");
    importBatch(
        token,
        rejectedPaycheck,
        List.of(
            importItem(refreshedFirst, "2026-01-31", 1400, true),
            importItem(refreshedFirst, "2026-02-28", 1500, true)),
        422);
    assertThat(entryCount(rejectedPaycheck)).isZero();
    assertThat(
            auditCount(
                "RECURRING_BILL_DEFINITION",
                UUID.fromString(first.path("id").asText()),
                "TYPICAL_AMOUNT_UPDATED_DURING_IMPORT"))
        .isEqualTo(1);
    assertThat(getDefinition(token, first.path("id").asText()).path("typicalAmountMinor").asLong())
        .isEqualTo(1300);

    JsonNode distinctUpdatesPaycheck =
        createPaycheck(token, "Distinct updates", 10000, "2026-01-15");
    importBatch(
        token,
        distinctUpdatesPaycheck,
        List.of(
            importItem(refreshedFirst, "2026-01-31", 1600, true),
            importItem(second, "2026-01-28", 2600, true)),
        200);
    assertThat(entryCount(distinctUpdatesPaycheck)).isEqualTo(2);
  }

  @Test
  void staleImportRevalidatesDefinitionAfterWaitingForItsLock() throws Exception {
    String token = register("recurring-stale-lock@yuuka.local");
    JsonNode definition = createDefinition(token, "Locked stale", 12000, 21, "AUTOPAY");
    JsonNode paycheck = createPaycheck(token, "Locked stale paycheck", 50000, "2026-07-17");
    UUID definitionId = UUID.fromString(definition.path("id").asText());
    UUID paycheckId = UUID.fromString(paycheck.path("id").asText());
    UUID ownerId = ownerIdForDefinition(definitionId);
    RecurringBillImportRequest request =
        importRequest(paycheck, definition, "2026-07-21", 12000, false);
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch importStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> update =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        lockDefinition(definitionId);
                        lockHeld.countDown();
                        await(importStarted);
                        jdbcTemplate.update(
                            "update recurring_bill_definitions "
                                + "set name = 'Updated before import', version = version + 1 "
                                + "where id = ?",
                            definitionId);
                      }));
      Future<String> importResult =
          executor.submit(
              () -> {
                await(lockHeld);
                importStarted.countDown();
                try {
                  recurringBillService.importIntoPaycheck(ownerId, paycheckId, request);
                  return "IMPORTED";
                } catch (ConflictException ex) {
                  return "CONFLICT";
                }
              });

      update.get(5, TimeUnit.SECONDS);
      assertThat(importResult.get(5, TimeUnit.SECONDS)).isEqualTo("CONFLICT");
      assertThat(entryCount(paycheck)).isZero();
      assertThat(actionAuditCount(ownerId, "RECURRING_BILL_IMPORTED")).isZero();
      assertThat(getDefinition(token, definition.path("id").asText()).path("name").asText())
          .isEqualTo("Updated before import");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void importHoldsDefinitionLockThroughSnapshotCreationWithoutTypicalUpdate() throws Exception {
    String token = register("recurring-snapshot-lock@yuuka.local");
    JsonNode definition = createDefinition(token, "Locked snapshot", 12000, 21, "AUTOPAY");
    JsonNode paycheck = createPaycheck(token, "Locked snapshot paycheck", 50000, "2026-07-17");
    UUID definitionId = UUID.fromString(definition.path("id").asText());
    UUID paycheckId = UUID.fromString(paycheck.path("id").asText());
    UUID ownerId = ownerIdForDefinition(definitionId);
    RecurringBillImportRequest request =
        importRequest(paycheck, definition, "2026-07-21", 12000, false);
    UpdateRecurringBillRequest updateRequest =
        new UpdateRecurringBillRequest(
            "Changed after snapshot",
            13000L,
            EntryPaymentMethod.MANUAL,
            22,
            "Changed account",
            "Changed payee",
            "Changed notes",
            definition.path("version").asLong());
    CountDownLatch tableLockHeld = new CountDownLatch(1);
    CountDownLatch releaseTableLock = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(3);

    try {
      Future<?> tableLock =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        jdbcTemplate.execute("lock table paycheck_entries in share mode");
                        tableLockHeld.countDown();
                        await(releaseTableLock);
                      }));
      await(tableLockHeld);
      Future<PaycheckResponse> importResult =
          executor.submit(
              () -> recurringBillService.importIntoPaycheck(ownerId, paycheckId, request));
      awaitDatabaseLock("insert into paycheck_entries%");

      Future<RecurringBillResponse> updateResult =
          executor.submit(() -> recurringBillService.update(ownerId, definitionId, updateRequest));
      awaitDatabaseLock("update recurring_bill_definitions%");
      assertThat(importResult.isDone()).isFalse();
      assertThat(updateResult.isDone()).isFalse();

      releaseTableLock.countDown();
      PaycheckResponse imported = importResult.get(5, TimeUnit.SECONDS);
      RecurringBillResponse updated = updateResult.get(5, TimeUnit.SECONDS);
      tableLock.get(5, TimeUnit.SECONDS);

      assertThat(imported.entries()).hasSize(1);
      assertThat(imported.entries().getFirst().name()).isEqualTo("Locked snapshot");
      assertThat(imported.entries().getFirst().paymentMethod())
          .isEqualTo(EntryPaymentMethod.AUTOPAY);
      assertThat(updated.name()).isEqualTo("Changed after snapshot");
      assertThat(
              jdbcTemplate.queryForObject(
                  "select name from paycheck_entries where paycheck_id = ?",
                  String.class,
                  paycheckId))
          .isEqualTo("Locked snapshot");
    } finally {
      releaseTableLock.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void createsDraftSnapshotsWithValidProvenanceAndRejectsInvalidCombinations() throws Exception {
    String token = register("recurring-draft@yuuka.local");
    JsonNode definition = createDefinition(token, "Water", 4500, 28, "AUTOPAY");
    String definitionId = definition.path("id").asText();

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-draft")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Draft import",
                      "amountMinor":5000,
                      "incomeDate":"2026-07-17",
                      "entries":[{
                        "entryType":"BILL",
                        "name":"Water",
                        "amountMinor":4500,
                        "sourceRecurringBillDefinitionId":"%s",
                        "sourceRecurringOccurrenceDate":"2026-07-28"
                      }]
                    }
                    """
                        .formatted(definitionId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.entries[0].sourceRecurringBillDefinitionId").value(definitionId))
        .andExpect(jsonPath("$.entries[0].sourceRecurringOccurrenceDate").value("2026-07-28"));

    UUID importedEntryId =
        jdbcTemplate.queryForObject(
            "select id from paycheck_entries where source_recurring_bill_definition_id = ?",
            UUID.class,
            UUID.fromString(definitionId));
    mockMvc
        .perform(
            patch("/api/v1/entries/{id}", importedEntryId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"SPENDING_BUCKET",
                      "name":"Water bucket",
                      "amountMinor":4500,
                      "version":0
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sourceRecurringBillDefinitionId").value((Object) null))
        .andExpect(jsonPath("$.sourceRecurringOccurrenceDate").value((Object) null));

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-draft")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Incomplete provenance",
                      "amountMinor":5000,
                      "incomeDate":"2026-07-17",
                      "entries":[{
                        "entryType":"BILL",
                        "name":"Water",
                        "amountMinor":4500,
                        "sourceRecurringBillDefinitionId":"%s"
                      }]
                    }
                    """
                        .formatted(definitionId)))
        .andExpect(status().isUnprocessableEntity());

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-draft")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Wrong entry type",
                      "amountMinor":5000,
                      "incomeDate":"2026-07-17",
                      "entries":[{
                        "entryType":"SPENDING_BUCKET",
                        "name":"Water",
                        "amountMinor":4500,
                        "sourceRecurringBillDefinitionId":"%s",
                        "sourceRecurringOccurrenceDate":"2026-07-28"
                      }]
                    }
                    """
                        .formatted(definitionId)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void persistsOwnerSuggestionWindowAndDatabaseConstraints() throws Exception {
    String token = register("recurring-settings@yuuka.local");
    mockMvc
        .perform(get("/api/v1/me").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recurringBillSuggestionDays").value(7));
    mockMvc
        .perform(
            patch("/api/v1/me/settings")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recurringBillSuggestionDays\":14}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.recurringBillSuggestionDays").value(14));
    mockMvc
        .perform(
            patch("/api/v1/me/settings")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recurringBillSuggestionDays\":32}"))
        .andExpect(status().isBadRequest());

    UUID ownerId = ownerIdForTokenEmail("recurring-settings@yuuka.local");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into recurring_bill_definitions
                      (id, owner_id, name, typical_amount_minor, payment_method, recurrence_type,
                       due_day, active, version, created_at, updated_at)
                    values (?, ?, 'Invalid', 1, 'AUTOPAY', 'MONTHLY', 32, true, 0, now(), now())
                    """,
                    UUID.randomUUID(),
                    ownerId))
        .isInstanceOf(DataAccessException.class);
  }

  private JsonNode createDefinition(
      String token, String name, long amountMinor, int dueDay, String paymentMethod)
      throws Exception {
    return requestJson(
        post("/api/v1/recurring-bills")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    Map.of(
                        "name", name,
                        "typicalAmountMinor", amountMinor,
                        "paymentMethod", paymentMethod,
                        "dueDay", dueDay,
                        "accountName", "Utility account",
                        "payee", name + " payee"))),
        201);
  }

  private JsonNode createPaycheck(String token, String name, long amountMinor, String incomeDate)
      throws Exception {
    return requestJson(
        post("/api/v1/paychecks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"%s","amountMinor":%d,"incomeDate":"%s"}
                """
                    .formatted(name, amountMinor, incomeDate)),
        201);
  }

  private JsonNode importBills(
      String token,
      JsonNode paycheck,
      JsonNode definition,
      String occurrenceDate,
      long amountMinor,
      boolean updateTypical,
      int expectedStatus)
      throws Exception {
    return requestJson(
        post("/api/v1/paychecks/{id}/recurring-bill-imports", paycheck.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "paycheckVersion":%d,
                  "items":[{
                    "definitionId":"%s",
                    "definitionVersion":%d,
                    "occurrenceDate":"%s",
                    "amountMinor":%d,
                    "updateTypicalAmount":%s
                  }]
                }
                """
                    .formatted(
                        paycheck.path("version").asLong(),
                        definition.path("id").asText(),
                        definition.path("version").asLong(),
                        occurrenceDate,
                        amountMinor,
                        updateTypical)),
        expectedStatus);
  }

  private JsonNode importBatch(
      String token, JsonNode paycheck, List<Map<String, Object>> items, int expectedStatus)
      throws Exception {
    return requestJson(
        post("/api/v1/paychecks/{id}/recurring-bill-imports", paycheck.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                objectMapper.writeValueAsString(
                    Map.of("paycheckVersion", paycheck.path("version").asLong(), "items", items))),
        expectedStatus);
  }

  private Map<String, Object> importItem(
      JsonNode definition, String occurrenceDate, long amountMinor, boolean updateTypicalAmount) {
    return Map.of(
        "definitionId", definition.path("id").asText(),
        "definitionVersion", definition.path("version").asLong(),
        "occurrenceDate", occurrenceDate,
        "amountMinor", amountMinor,
        "updateTypicalAmount", updateTypicalAmount);
  }

  private RecurringBillImportRequest importRequest(
      JsonNode paycheck,
      JsonNode definition,
      String occurrenceDate,
      long amountMinor,
      boolean updateTypicalAmount) {
    return new RecurringBillImportRequest(
        paycheck.path("version").asLong(),
        List.of(
            new RecurringBillImportItemRequest(
                UUID.fromString(definition.path("id").asText()),
                definition.path("version").asLong(),
                LocalDate.parse(occurrenceDate),
                amountMinor,
                updateTypicalAmount)));
  }

  private long entryCount(JsonNode paycheck) {
    return jdbcTemplate.queryForObject(
        "select count(*) from paycheck_entries where paycheck_id = ?",
        Long.class,
        UUID.fromString(paycheck.path("id").asText()));
  }

  private long auditCount(String entityType, UUID entityId, String action) {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_events where entity_type = ? and entity_id = ? and action = ?",
        Long.class,
        entityType,
        entityId,
        action);
  }

  private long actionAuditCount(UUID ownerId, String action) {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_events where owner_id = ? and action = ?",
        Long.class,
        ownerId,
        action);
  }

  private UUID ownerIdForDefinition(UUID definitionId) {
    return jdbcTemplate.queryForObject(
        "select owner_id from recurring_bill_definitions where id = ?", UUID.class, definitionId);
  }

  private void lockDefinition(UUID definitionId) {
    jdbcTemplate.queryForObject(
        "select id from recurring_bill_definitions where id = ? for update",
        UUID.class,
        definitionId);
  }

  private void awaitDatabaseLock(String queryPattern) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      Long blocked =
          jdbcTemplate.queryForObject(
              "select count(*) from pg_stat_activity "
                  + "where datname = current_database() and wait_event_type = 'Lock' and query like ?",
              Long.class,
              queryPattern);
      if (blocked != null && blocked > 0) return;
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
    }
    throw new AssertionError("Timed out waiting for database lock: " + queryPattern);
  }

  private void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
  }

  private JsonNode getDefinition(String token, String id) throws Exception {
    return requestJson(
        get("/api/v1/recurring-bills/{id}", id).header("Authorization", bearer(token)), 200);
  }

  private JsonNode requestJson(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String content = result.getResponse().getContentAsString();
    return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
  }

  private String register(String email) throws Exception {
    JsonNode response =
        requestJson(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"Password12345","displayName":"Test"}
                    """
                        .formatted(email)),
            201);
    return response.path("accessToken").asText();
  }

  private UUID ownerIdForTokenEmail(String email) {
    return jdbcTemplate.queryForObject(
        "select id from user_accounts where email = ?", UUID.class, email);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
