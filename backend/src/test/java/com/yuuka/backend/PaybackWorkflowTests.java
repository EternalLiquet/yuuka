package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.api.dto.CreateEntryRequest;
import com.yuuka.backend.paycheck.api.dto.StatusChangeRequest;
import com.yuuka.backend.paycheck.application.PaycheckService;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.time.Instant;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class PaybackWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private PaybackService paybackService;
  @Autowired private PaycheckService paycheckService;
  @Autowired private JpaPaycheckEntryRepository entryRepository;

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
        .andExpect(jsonPath("$.items[0].position").value(0))
        .andExpect(jsonPath("$.items[1].state").value("PAID_OFF"))
        .andExpect(jsonPath("$.items[1].position").value(1));
  }

  @Test
  void paybackSummaryOverflowUsesBusinessRuleEnvelope() throws Exception {
    String token = registerAndGetAccessToken("paybacks-summary-overflow@yuuka.local");

    createPayback(token, "Huge one", Long.MAX_VALUE, Long.MAX_VALUE);
    createPayback(token, "Huge two", Long.MAX_VALUE, Long.MAX_VALUE);

    mockMvc
        .perform(get("/api/v1/paybacks").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("MONEY_AMOUNT_OVERFLOW"))
        .andExpect(jsonPath("$.details.currencyCode").value("USD"));
  }

  @Test
  void deletesUnusedPaybackAndRemovesItFromNormalReads() throws Exception {
    String token = registerAndGetAccessToken("paybacks-delete-unused@yuuka.local");
    JsonNode payback = createPayback(token, "Unused", 10000, 10000);
    UUID paybackId = UUID.fromString(payback.path("id").asText());

    deletePayback(token, payback.path("id").asText(), payback.path("version").asLong(), 204);

    mockMvc
        .perform(
            get("/api/v1/paybacks/{id}", payback.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/paybacks").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    assertThat(deletedAtForPayback(paybackId)).isNotNull();
    assertThat(auditCount("PAYBACK", paybackId, "DELETED")).isEqualTo(1);
  }

  @Test
  void deletingPaybackUnassignsLiveEntriesAndPreservesEntryFields() throws Exception {
    String token = registerAndGetAccessToken("paybacks-delete-unassign@yuuka.local");
    JsonNode payback = createPayback(token, "Assigned", 10000, 10000);
    JsonNode nextPayback = createPayback(token, "Next", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "Unposted repayment",
            3000,
            payback.path("id").asText());
    UUID entryId = UUID.fromString(entry.path("id").asText());
    Instant paycheckUpdatedAt = updatedAtForPaycheck(UUID.fromString(paycheck.path("id").asText()));

    deletePayback(token, payback.path("id").asText(), payback.path("version").asLong(), 204);

    JsonNode refreshedEntry =
        getEntryFromPaycheck(token, paycheck.path("id").asText(), entry.path("id").asText());
    assertThat(refreshedEntry.path("paybackId").isNull()).isTrue();
    assertThat(refreshedEntry.path("status").asText()).isEqualTo("NOT_PAID");
    assertThat(refreshedEntry.path("amountMinor").asLong()).isEqualTo(3000);
    assertThat(refreshedEntry.path("entryType").asText()).isEqualTo("BILL");
    assertThat(refreshedEntry.path("name").asText()).isEqualTo("Unposted repayment");
    assertThat(refreshedEntry.path("position").asInt()).isEqualTo(0);
    assertThat(refreshedEntry.path("version").asLong())
        .isGreaterThan(entry.path("version").asLong());
    assertThat(updatedAtForPaycheck(UUID.fromString(paycheck.path("id").asText())))
        .isAfter(paycheckUpdatedAt);
    assertThat(auditCount("PAYCHECK_ENTRY", entryId, "PAYBACK_UNASSIGNED_DUE_TO_DELETION"))
        .isEqualTo(1);

    JsonNode reassigned =
        updateEntryPayback(
            token,
            entry.path("id").asText(),
            refreshedEntry.path("version").asLong(),
            nextPayback.path("id").asText(),
            200);
    assertThat(reassigned.path("paybackId").asText()).isEqualTo(nextPayback.path("id").asText());
  }

  @Test
  void deletingPaybackWithPostedEntryReversesRepaymentButLeavesEntryPosted() throws Exception {
    String token = registerAndGetAccessToken("paybacks-delete-posted@yuuka.local");
    JsonNode payback = createPayback(token, "Posted", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "Posted repayment",
            4000,
            payback.path("id").asText());
    JsonNode posted =
        changeStatus(token, entry.path("id").asText(), "POSTED", entry.path("version").asLong());
    changeStatus(token, entry.path("id").asText(), "PROCESSING", posted.path("version").asLong());
    JsonNode processing =
        getEntryFromPaycheck(token, paycheck.path("id").asText(), entry.path("id").asText());
    JsonNode postedAgain =
        changeStatus(
            token, entry.path("id").asText(), "POSTED", processing.path("version").asLong());

    deletePayback(
        token,
        payback.path("id").asText(),
        getPayback(token, payback.path("id").asText()).path("version").asLong(),
        204);

    JsonNode refreshedEntry =
        getEntryFromPaycheck(token, paycheck.path("id").asText(), entry.path("id").asText());
    assertThat(refreshedEntry.path("status").asText()).isEqualTo("POSTED");
    assertThat(refreshedEntry.path("paybackId").isNull()).isTrue();
    UUID paybackId = UUID.fromString(payback.path("id").asText());
    assertThat(activeRepaymentCount(paybackId)).isZero();
    assertThat(totalRepaymentCount(paybackId)).isEqualTo(2);
    assertThat(reversedRepaymentCount(paybackId)).isEqualTo(2);
    assertThat(postedAgain.path("status").asText()).isEqualTo("POSTED");
  }

  @Test
  void staleAndCrossOwnerPaybackDeletesAreRejected() throws Exception {
    String ownerToken = registerAndGetAccessToken("paybacks-delete-owner@yuuka.local");
    String otherToken = registerAndGetAccessToken("paybacks-delete-other@yuuka.local");
    JsonNode payback = createPayback(ownerToken, "Private delete", 10000, 10000);

    deletePayback(otherToken, payback.path("id").asText(), payback.path("version").asLong(), 404);
    deletePayback(
        ownerToken, payback.path("id").asText(), payback.path("version").asLong() + 1, 409);
    assertThat(deletedAtForPayback(UUID.fromString(payback.path("id").asText()))).isNull();
  }

  @Test
  void reordersPaybacksAndRejectsInvalidOrders() throws Exception {
    String token = registerAndGetAccessToken("paybacks-reorder@yuuka.local");
    JsonNode first = createPayback(token, "First", 10000, 10000);
    JsonNode second = createPayback(token, "Second", 10000, 10000);
    JsonNode third = createPayback(token, "Third", 10000, 0);

    JsonNode reordered =
        reorderPaybacks(
            token,
            List.of(
                third.path("id").asText(), first.path("id").asText(), second.path("id").asText()),
            200);
    assertThat(reordered.path("items").get(0).path("id").asText())
        .isEqualTo(first.path("id").asText());
    assertThat(reordered.path("items").get(1).path("id").asText())
        .isEqualTo(second.path("id").asText());
    assertThat(reordered.path("items").get(2).path("id").asText())
        .isEqualTo(third.path("id").asText());
    assertThat(positionForPayback(UUID.fromString(third.path("id").asText()))).isZero();
    assertThat(positionForPayback(UUID.fromString(first.path("id").asText()))).isEqualTo(1);

    reorderPaybacks(
        token,
        List.of(first.path("id").asText(), first.path("id").asText(), second.path("id").asText()),
        422);
    reorderPaybacks(token, List.of(first.path("id").asText(), second.path("id").asText()), 422);
    deletePayback(
        token,
        second.path("id").asText(),
        getPayback(token, second.path("id").asText()).path("version").asLong(),
        204);
    reorderPaybacks(
        token,
        List.of(first.path("id").asText(), second.path("id").asText(), third.path("id").asText()),
        422);
  }

  @Test
  void appliesAndReversesPostedEntryRepaymentsExactlyOnce() throws Exception {
    String token = registerAndGetAccessToken("paybacks-lifecycle@yuuka.local");
    JsonNode payback = createPayback(token, "Car repair", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 20000);
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
  void paybackListBatchedRepaymentAggregatesMatchDetailResponses() throws Exception {
    String token = registerAndGetAccessToken("paybacks-list-batch@yuuka.local");
    JsonNode activePayback = createPayback(token, "Active aggregate", 10000, 10000);
    JsonNode reversedPayback = createPayback(token, "Reversed aggregate", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 20000);

    JsonNode activeEntry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "Active repayment",
            4000,
            activePayback.path("id").asText());
    changeStatus(
        token, activeEntry.path("id").asText(), "POSTED", activeEntry.path("version").asLong());

    JsonNode reversedEntry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "Reversed repayment",
            2500,
            reversedPayback.path("id").asText());
    JsonNode postedReversed =
        changeStatus(
            token,
            reversedEntry.path("id").asText(),
            "POSTED",
            reversedEntry.path("version").asLong());
    changeStatus(
        token,
        reversedEntry.path("id").asText(),
        "PROCESSING",
        postedReversed.path("version").asLong());

    JsonNode list =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/v1/paybacks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    JsonNode activeDetail = getPayback(token, activePayback.path("id").asText());
    JsonNode reversedDetail = getPayback(token, reversedPayback.path("id").asText());
    assertPaybackListItemMatchesDetail(
        itemById(list.path("items"), activePayback.path("id").asText()), activeDetail);
    assertPaybackListItemMatchesDetail(
        itemById(list.path("items"), reversedPayback.path("id").asText()), reversedDetail);
    assertThat(list.path("summary").path("totalRepaidMinor").asLong()).isEqualTo(4000);
    assertThat(list.path("summary").path("totalRemainingMinor").asLong()).isEqualTo(16000);
    assertThat(list.path("summary").path("activeCount").asInt()).isEqualTo(2);
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
  void deleteRechecksAssignmentHistoryAfterWaitingForPaybackLock() throws Exception {
    String token = registerAndGetAccessToken("paybacks-delete-race@yuuka.local");
    JsonNode payback = createPayback(token, "Delete race", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry = createEntry(token, paycheck.path("id").asText(), "Race entry", 1000, null);
    UUID paybackId = UUID.fromString(payback.path("id").asText());
    UUID entryId = UUID.fromString(entry.path("id").asText());
    UUID ownerId = ownerIdForEntry(entryId);
    long version = payback.path("version").asLong();
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> assignment =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        lockPayback(paybackId);
                        lockHeld.countDown();
                        await(deleteStarted);
                        jdbcTemplate.update(
                            "update paycheck_entries set payback_id = ? where id = ?",
                            paybackId,
                            entryId);
                      }));
      Future<String> deletion =
          executor.submit(
              () -> {
                await(lockHeld);
                deleteStarted.countDown();
                try {
                  paybackService.delete(ownerId, paybackId, version);
                  return "DELETED";
                } catch (BusinessRuleException ex) {
                  return ex.code();
                }
              });

      assignment.get(5, TimeUnit.SECONDS);

      assertThat(deletion.get(5, TimeUnit.SECONDS)).isEqualTo("DELETED");
      assertThat(deletedAtForPayback(paybackId)).isNotNull();
      assertThat(paybackIdForEntry(entryId)).isNull();
      assertThat(activeRepaymentCount(paybackId)).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void assignmentValidationRechecksPaybackAfterWaitingForPaybackLock() throws Exception {
    String token = registerAndGetAccessToken("paybacks-assignment-race@yuuka.local");
    JsonNode payback = createPayback(token, "Assignment race", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    UUID paybackId = UUID.fromString(payback.path("id").asText());
    UUID paycheckId = UUID.fromString(paycheck.path("id").asText());
    UUID ownerId = ownerIdForPayback(paybackId);
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch assignmentStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> deletion =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        lockPayback(paybackId);
                        lockHeld.countDown();
                        await(assignmentStarted);
                        jdbcTemplate.update(
                            "update paybacks set deleted_at = now() where id = ?", paybackId);
                      }));
      Future<String> assignment =
          executor.submit(
              () -> {
                await(lockHeld);
                assignmentStarted.countDown();
                try {
                  paycheckService.addEntry(
                      ownerId,
                      paycheckId,
                      new CreateEntryRequest(
                          EntryType.BILL,
                          "Late assignment",
                          1000L,
                          null,
                          null,
                          null,
                          null,
                          null,
                          null,
                          null,
                          paybackId,
                          null));
                  return "ASSIGNED";
                } catch (ResourceNotFoundException ex) {
                  return "NOT_FOUND";
                }
              });

      deletion.get(5, TimeUnit.SECONDS);

      assertThat(assignment.get(5, TimeUnit.SECONDS)).isEqualTo("NOT_FOUND");
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from paycheck_entries where paycheck_id = ? and payback_id = ?",
                  Long.class,
                  paycheckId,
                  paybackId))
          .isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentPostedRepaymentsCannotOverpayPayback() throws Exception {
    String token = registerAndGetAccessToken("paybacks-concurrent-post@yuuka.local");
    JsonNode payback = createPayback(token, "Concurrent repayment", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 20000);
    JsonNode firstEntry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "First repayment",
            6000,
            payback.path("id").asText());
    JsonNode secondEntry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "Second repayment",
            6000,
            payback.path("id").asText());
    UUID paybackId = UUID.fromString(payback.path("id").asText());
    UUID ownerId = ownerIdForPayback(paybackId);
    UUID firstEntryId = UUID.fromString(firstEntry.path("id").asText());
    UUID secondEntryId = UUID.fromString(secondEntry.path("id").asText());
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch bothPosting = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(3);

    try {
      Future<?> lockHolder =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        lockPayback(paybackId);
                        lockHeld.countDown();
                        await(bothPosting);
                      }));
      Future<String> firstPost =
          executor.submit(
              () ->
                  postEntryStatusAfterLock(
                      lockHeld,
                      bothPosting,
                      ownerId,
                      firstEntryId,
                      firstEntry.path("version").asLong()));
      Future<String> secondPost =
          executor.submit(
              () ->
                  postEntryStatusAfterLock(
                      lockHeld,
                      bothPosting,
                      ownerId,
                      secondEntryId,
                      secondEntry.path("version").asLong()));

      lockHolder.get(5, TimeUnit.SECONDS);

      assertThat(List.of(firstPost.get(5, TimeUnit.SECONDS), secondPost.get(5, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("POSTED", "PAYBACK_REPAYMENT_OVERPAID");
      assertThat(activeRepaymentCount(paybackId)).isEqualTo(1);
      assertThat(activeRepaymentSum(paybackId)).isEqualTo(6000);
      assertThat(postedEntryCount(paycheck.path("id").asText())).isEqualTo(1);
      JsonNode current = getPayback(token, payback.path("id").asText());
      assertThat(current.path("remainingMinor").asLong()).isEqualTo(4000);
      assertThat(current.path("state").asText()).isEqualTo("ACTIVE");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void reapplyingExistingRepaymentToPaidOffPaybackIsNoOp() throws Exception {
    String token = registerAndGetAccessToken("paybacks-paid-idempotent@yuuka.local");
    JsonNode payback = createPayback(token, "Paid idempotency", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
    JsonNode entry =
        createEntry(
            token,
            paycheck.path("id").asText(),
            "Final repayment",
            10000,
            payback.path("id").asText());
    changeStatus(token, entry.path("id").asText(), "POSTED", entry.path("version").asLong());
    JsonNode paid = getPayback(token, payback.path("id").asText());
    UUID entryId = UUID.fromString(entry.path("id").asText());
    UUID ownerId = ownerIdForEntry(entryId);
    PaycheckEntry persistedEntry =
        entryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(entryId, ownerId).orElseThrow();

    paybackService.applyPostedEntryRepayment(
        ownerId, persistedEntry, Instant.parse("2026-07-17T13:00:00Z"));

    JsonNode afterRetry = getPayback(token, payback.path("id").asText());
    assertThat(afterRetry.path("state").asText()).isEqualTo("PAID_OFF");
    assertThat(afterRetry.path("remainingMinor").asLong()).isZero();
    assertThat(afterRetry.path("repaymentCount").asLong()).isEqualTo(1);
    assertThat(afterRetry.path("version").asLong()).isEqualTo(paid.path("version").asLong());
    assertThat(afterRetry.path("updatedAt").asText()).isEqualTo(paid.path("updatedAt").asText());
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

  private JsonNode getPaycheck(String token, String paycheckId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/paychecks/{id}", paycheckId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode getEntryFromPaycheck(String token, String paycheckId, String entryId)
      throws Exception {
    for (JsonNode entry : getPaycheck(token, paycheckId).path("entries")) {
      if (entry.path("id").asText().equals(entryId)) {
        return entry;
      }
    }
    throw new AssertionError("Entry not found in paycheck response: " + entryId);
  }

  private JsonNode itemById(JsonNode items, String id) {
    for (JsonNode item : items) {
      if (id.equals(item.path("id").asText())) {
        return item;
      }
    }
    throw new AssertionError("Missing response item " + id);
  }

  private void assertPaybackListItemMatchesDetail(JsonNode listItem, JsonNode detail) {
    assertThat(listItem.path("repaidMinor").asLong())
        .isEqualTo(detail.path("repaidMinor").asLong());
    assertThat(listItem.path("remainingMinor").asLong())
        .isEqualTo(detail.path("remainingMinor").asLong());
    assertThat(listItem.path("progressPercent").asDouble())
        .isEqualTo(detail.path("progressPercent").asDouble());
    assertThat(listItem.path("state").asText()).isEqualTo(detail.path("state").asText());
    assertThat(listItem.path("repaymentCount").asLong())
        .isEqualTo(detail.path("repaymentCount").asLong());
  }

  private void deletePayback(String token, String paybackId, long version, int expectedStatus)
      throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/paybacks/{id}?version={version}", paybackId, version)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private JsonNode reorderPaybacks(String token, List<String> paybackIds, int expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paybacks/reorder")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("paybackIds", paybackIds))))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode updateEntryPayback(
      String token, String entryId, long version, String paybackId, int expectedStatus)
      throws Exception {
    String paybackJson = paybackId == null ? "null" : "\"%s\"".formatted(paybackId);
    MvcResult result =
        mockMvc
            .perform(
                patch("/api/v1/entries/{id}", entryId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "entryType": "BILL",
                          "name": "Unposted repayment",
                          "amountMinor": 3000,
                          "paybackId": %s,
                          "version": %d
                        }
                        """
                            .formatted(paybackJson, version)))
            .andExpect(status().is(expectedStatus))
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

  private String postEntryStatusAfterLock(
      CountDownLatch lockHeld,
      CountDownLatch bothPosting,
      UUID ownerId,
      UUID entryId,
      long version) {
    await(lockHeld);
    bothPosting.countDown();
    try {
      paycheckService.changeStatus(
          ownerId,
          entryId,
          new StatusChangeRequest(
              EntryStatus.POSTED, Instant.parse("2026-07-17T12:00:00Z"), null, version));
      return "POSTED";
    } catch (BusinessRuleException ex) {
      return ex.code();
    }
  }

  private void lockPayback(UUID paybackId) {
    jdbcTemplate.queryForObject(
        "select id from paybacks where id = ? for update", UUID.class, paybackId);
  }

  private UUID ownerIdForPayback(UUID paybackId) {
    return jdbcTemplate.queryForObject(
        "select owner_id from paybacks where id = ?", UUID.class, paybackId);
  }

  private UUID ownerIdForEntry(UUID entryId) {
    return jdbcTemplate.queryForObject(
        "select owner_id from paycheck_entries where id = ?", UUID.class, entryId);
  }

  private UUID paybackIdForEntry(UUID entryId) {
    return jdbcTemplate.queryForObject(
        "select payback_id from paycheck_entries where id = ?", UUID.class, entryId);
  }

  private Instant deletedAtForPayback(UUID paybackId) {
    return jdbcTemplate.queryForObject(
        "select deleted_at from paybacks where id = ?", Instant.class, paybackId);
  }

  private Instant updatedAtForPaycheck(UUID paycheckId) {
    return jdbcTemplate.queryForObject(
        "select updated_at from paychecks where id = ?", Instant.class, paycheckId);
  }

  private int positionForPayback(UUID paybackId) {
    return jdbcTemplate.queryForObject(
        "select position from paybacks where id = ?", Integer.class, paybackId);
  }

  private long auditCount(String entityType, UUID entityId, String action) {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_events where entity_type = ? and entity_id = ? and action = ?",
        Long.class,
        entityType,
        entityId,
        action);
  }

  private long activeRepaymentCount(UUID paybackId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from payback_repayments where payback_id = ? and reversed_at is null",
        Long.class,
        paybackId);
  }

  private long totalRepaymentCount(UUID paybackId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from payback_repayments where payback_id = ?", Long.class, paybackId);
  }

  private long reversedRepaymentCount(UUID paybackId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from payback_repayments where payback_id = ? and reversed_at is not null",
        Long.class,
        paybackId);
  }

  private long activeRepaymentSum(UUID paybackId) {
    return jdbcTemplate.queryForObject(
        "select coalesce(sum(amount_minor), 0) from payback_repayments "
            + "where payback_id = ? and reversed_at is null",
        Long.class,
        paybackId);
  }

  private long postedEntryCount(String paycheckId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from paycheck_entries where paycheck_id = ? and status = 'POSTED'",
        Long.class,
        UUID.fromString(paycheckId));
  }

  private void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AssertionError(ex);
    }
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
