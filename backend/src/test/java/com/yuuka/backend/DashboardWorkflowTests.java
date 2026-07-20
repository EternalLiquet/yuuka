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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DashboardWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MutableClock clock;

  @BeforeEach
  void resetClock() {
    clock.setInstant(Instant.parse("2026-07-20T03:00:00Z"));
  }

  @Test
  void returnsEmptyDashboardAndOwnerScopedFinancialPositions() throws Exception {
    Owner empty = register("dashboard-empty@yuuka.local", "America/Indianapolis");
    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(empty.token())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOfDate").value("2026-07-19"))
        .andExpect(jsonPath("$.needsAttention.length()").value(0))
        .andExpect(jsonPath("$.active.paycheckCount").value(0))
        .andExpect(jsonPath("$.active.totalUnallocatedMinor").value(0))
        .andExpect(jsonPath("$.active.previews.length()").value(0))
        .andExpect(jsonPath("$.paybacks.totalRemainingMinor").value(0))
        .andExpect(jsonPath("$.plannedSavings.totalActiveReservedBalanceMinor").value(0))
        .andExpect(jsonPath("$.expenseLists.openCount").value(0));

    Owner owner = register("dashboard-positions@yuuka.local", "America/Indianapolis");
    Owner other = register("dashboard-positions-other@yuuka.local", "America/Indianapolis");
    insertPayback(owner.id(), 1200, "ACTIVE", false, 0);
    insertPayback(owner.id(), 0, "PAID_OFF", false, 1);
    insertPayback(owner.id(), 9000, "ACTIVE", true, 2);
    insertPayback(other.id(), 8800, "ACTIVE", false, 0);
    insertFund(owner.id(), "ACTIVE", 500, 0);
    insertFund(owner.id(), "ARCHIVED", 700, 1);
    insertFund(other.id(), "ACTIVE", 9900, 0);
    insertLedger(owner.id(), "Open", "OPEN", false, 0);
    insertLedger(owner.id(), "Finalized", "FINALIZED", false, 2500);
    insertLedger(owner.id(), "Deleted", "OPEN", true, 0);
    insertLedger(other.id(), "Other", "FINALIZED", false, 9900);

    mockMvc
        .perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(owner.token())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paybacks.totalRemainingMinor").value(1200))
        .andExpect(jsonPath("$.paybacks.activeCount").value(1))
        .andExpect(jsonPath("$.plannedSavings.totalActiveReservedBalanceMinor").value(500))
        .andExpect(jsonPath("$.plannedSavings.activeCount").value(1))
        .andExpect(jsonPath("$.expenseLists.openCount").value(1))
        .andExpect(jsonPath("$.expenseLists.finalizedCount").value(1))
        .andExpect(jsonPath("$.needsAttention.length()").value(1))
        .andExpect(jsonPath("$.needsAttention[0].name").value("Finalized"));
  }

  @Test
  void returnsEveryAttentionConditionAtTheThreeDayBoundaryInPriorityOrder() throws Exception {
    Owner owner = register("dashboard-attention@yuuka.local", "America/Indianapolis");
    UUID paycheckId = insertPaycheck(owner.id(), "Attention check", 10000, "2026-07-18", "ACTIVE");
    UUID manualId =
        insertEntry(
            owner.id(),
            paycheckId,
            "BILL",
            "Manual utility",
            1000,
            "NOT_PAID",
            0,
            "MANUAL",
            "2026-07-18",
            false);
    UUID processingId =
        insertEntry(
            owner.id(),
            paycheckId,
            "BILL",
            "Slow transfer",
            2000,
            "PROCESSING",
            1,
            "AUTOPAY",
            null,
            false);
    insertStatusEvent(
        owner.id(), processingId, "PROCESSING", "2026-07-16T12:00:00Z", "2026-07-16T12:00:00Z");
    UUID recentProcessingId =
        insertEntry(
            owner.id(),
            paycheckId,
            "BILL",
            "Recent transfer",
            1000,
            "PROCESSING",
            2,
            "AUTOPAY",
            null,
            false);
    insertStatusEvent(
        owner.id(),
        recentProcessingId,
        "PROCESSING",
        "2026-07-17T12:00:00Z",
        "2026-07-17T12:00:00Z");
    UUID bucketId =
        insertEntry(
            owner.id(),
            paycheckId,
            "SPENDING_BUCKET",
            "Groceries",
            1000,
            "NOT_PAID",
            3,
            null,
            null,
            false);
    insertBucketTransaction(owner.id(), bucketId, 1500, false);
    insertLedger(owner.id(), "Trip expenses", "FINALIZED", false, 3000);

    JsonNode response = summary(owner.token());
    assertThat(response.path("needsAttention").findValuesAsText("kind"))
        .containsExactly(
            "MANUAL_BILL_NOT_PAID",
            "UNALLOCATED_PAYCHECK",
            "PROCESSING_ENTRY",
            "OVER_BUDGET_BUCKET",
            "FINALIZED_EXPENSE_LEDGER");
    assertThat(response.path("needsAttention").get(0).path("entryId").asText())
        .isEqualTo(manualId.toString());
    assertThat(response.path("needsAttention").get(2).path("entryId").asText())
        .isEqualTo(processingId.toString());
    assertThat(response.toString()).doesNotContain(recentProcessingId.toString());
    assertThat(response.path("needsAttention").get(3).path("amountMinor").asLong()).isEqualTo(500);
    assertThat(response.path("active").path("paycheckCount").asLong()).isEqualTo(1);
    assertThat(response.path("active").path("totalUnallocatedMinor").asLong()).isEqualTo(5000);
    assertThat(response.path("active").path("notPaidEntryCount").asLong()).isEqualTo(2);
    assertThat(response.path("active").path("processingEntryCount").asLong()).isEqualTo(2);
  }

  @Test
  void usesOwnerLocalCalendarDatesForProcessingAttention() throws Exception {
    Owner indianapolis = register("dashboard-zone-east@yuuka.local", "America/Indianapolis");
    Owner losAngeles = register("dashboard-zone-west@yuuka.local", "America/Los_Angeles");
    UUID eastEntry = insertProcessingEntry(indianapolis.id());
    UUID westEntry = insertProcessingEntry(losAngeles.id());
    String effectiveAt = "2026-07-17T05:00:00Z";
    insertStatusEvent(indianapolis.id(), eastEntry, "PROCESSING", effectiveAt, effectiveAt);
    insertStatusEvent(losAngeles.id(), westEntry, "PROCESSING", effectiveAt, effectiveAt);

    JsonNode east = summary(indianapolis.token());
    JsonNode west = summary(losAngeles.token());
    assertThat(east.path("asOfDate").asText()).isEqualTo("2026-07-19");
    assertThat(west.path("asOfDate").asText()).isEqualTo("2026-07-19");
    assertThat(east.toString()).doesNotContain("PROCESSING_ENTRY");
    assertThat(west.path("needsAttention").get(0).path("kind").asText())
        .isEqualTo("PROCESSING_ENTRY");
    assertThat(west.path("needsAttention").get(0).path("attentionSinceDate").asText())
        .isEqualTo("2026-07-16");
  }

  @Test
  void capsAttentionDeterministicallyAndExcludesDeletedAndInactiveRecords() throws Exception {
    Owner owner = register("dashboard-cap@yuuka.local", "America/Indianapolis");
    List<UUID> paycheckIds = new ArrayList<>();
    for (int index = 0; index < 6; index++) {
      paycheckIds.add(insertPaycheck(owner.id(), "Tie " + index, 1000, "2026-07-10", "ACTIVE"));
    }
    UUID deletedEntryPaycheck =
        insertPaycheck(owner.id(), "Deleted entry only", 0, "2026-07-11", "ACTIVE");
    insertEntry(
        owner.id(),
        deletedEntryPaycheck,
        "BILL",
        "Deleted manual",
        0,
        "NOT_PAID",
        0,
        "MANUAL",
        "2026-07-01",
        true);
    UUID closed = insertPaycheck(owner.id(), "Closed", 1000, "2026-07-12", "CLOSED");
    insertEntry(
        owner.id(),
        closed,
        "BILL",
        "Closed manual",
        1000,
        "NOT_PAID",
        0,
        "MANUAL",
        "2026-07-01",
        false);
    insertLedger(owner.id(), "Deleted finalized", "FINALIZED", true, 500);
    insertLedger(owner.id(), "Settled", "SETTLED", false, 500);

    JsonNode response = summary(owner.token());
    assertThat(response.path("needsAttention").size()).isEqualTo(5);
    List<String> expected = paycheckIds.stream().sorted().limit(5).map(UUID::toString).toList();
    assertThat(response.path("needsAttention").findValuesAsText("paycheckId"))
        .containsExactlyElementsOf(expected);
    assertThat(response.toString())
        .doesNotContain("Deleted manual", "Closed manual", "Deleted finalized", "Settled");
  }

  @Test
  void calculatesAndPrioritizesTwoCompactActivePreviews() throws Exception {
    Owner owner = register("dashboard-previews@yuuka.local", "America/Indianapolis");
    UUID first = insertPaycheck(owner.id(), "More work", 2000, "2026-07-10", "ACTIVE");
    insertEntry(owner.id(), first, "BILL", "One", 1000, "NOT_PAID", 0, "AUTOPAY", null, false);
    UUID second = insertPaycheck(owner.id(), "Newer", 1000, "2026-07-18", "ACTIVE");
    UUID third = insertPaycheck(owner.id(), "Allocated", 2000, "2026-07-19", "ACTIVE");
    insertEntry(owner.id(), third, "BILL", "A", 1000, "PROCESSING", 0, "AUTOPAY", null, false);
    insertEntry(owner.id(), third, "BILL", "B", 1000, "NOT_PAID", 1, "AUTOPAY", null, false);

    JsonNode active = summary(owner.token()).path("active");
    assertThat(active.path("paycheckCount").asLong()).isEqualTo(3);
    assertThat(active.path("totalUnallocatedMinor").asLong()).isEqualTo(2000);
    assertThat(active.path("notPaidEntryCount").asLong()).isEqualTo(2);
    assertThat(active.path("processingEntryCount").asLong()).isEqualTo(1);
    assertThat(active.path("previews").size()).isEqualTo(2);
    assertThat(active.path("previews").findValuesAsText("paycheckId"))
        .containsExactly(first.toString(), second.toString());
  }

  private UUID insertProcessingEntry(UUID ownerId) {
    UUID paycheckId = insertPaycheck(ownerId, "Processing", 1000, "2026-07-18", "ACTIVE");
    return insertEntry(
        ownerId, paycheckId, "BILL", "Processing", 1000, "PROCESSING", 0, "AUTOPAY", null, false);
  }

  private UUID insertPaycheck(
      UUID ownerId, String name, long amountMinor, String incomeDate, String state) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        insert into paychecks
          (id, owner_id, name, amount_minor, income_date, state, created_at, updated_at, version)
        values (?, ?, ?, ?, cast(? as date), ?, now(), now(), 0)
        """,
        id,
        ownerId,
        name,
        amountMinor,
        incomeDate,
        state);
    return id;
  }

  private UUID insertEntry(
      UUID ownerId,
      UUID paycheckId,
      String type,
      String name,
      long amountMinor,
      String entryStatus,
      int position,
      String paymentMethod,
      String dueDate,
      boolean deleted) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        insert into paycheck_entries
          (id, owner_id, paycheck_id, entry_type, payment_method, name, amount_minor, status,
           position, due_date, created_at, updated_at, deleted_at, version)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as date), now(), now(), ?, 0)
        """,
        id,
        ownerId,
        paycheckId,
        type,
        paymentMethod,
        name,
        amountMinor,
        entryStatus,
        position,
        dueDate,
        deleted ? Timestamp.from(Instant.parse("2026-07-18T00:00:00Z")) : null);
    return id;
  }

  private void insertStatusEvent(
      UUID ownerId, UUID entryId, String toStatus, String effectiveAt, String recordedAt) {
    jdbcTemplate.update(
        """
        insert into entry_status_events
          (id, owner_id, entry_id, from_status, to_status, effective_at, recorded_at)
        values (?, ?, ?, 'NOT_PAID', ?, cast(? as timestamptz), cast(? as timestamptz))
        """,
        UUID.randomUUID(),
        ownerId,
        entryId,
        toStatus,
        effectiveAt,
        recordedAt);
  }

  private void insertBucketTransaction(
      UUID ownerId, UUID entryId, long amountMinor, boolean deleted) {
    jdbcTemplate.update(
        """
        insert into bucket_transactions
          (id, owner_id, entry_id, amount_minor, effective_date, created_at, updated_at, deleted_at, version)
        values (?, ?, ?, ?, '2026-07-18', now(), now(), ?, 0)
        """,
        UUID.randomUUID(),
        ownerId,
        entryId,
        amountMinor,
        deleted ? Timestamp.from(Instant.parse("2026-07-18T00:00:00Z")) : null);
  }

  private void insertPayback(
      UUID ownerId, long remainingMinor, String state, boolean deleted, int position) {
    jdbcTemplate.update(
        """
        insert into paybacks
          (id, owner_id, name, original_amount_minor, opening_remaining_amount_minor, borrowed_date,
           state, position, created_at, updated_at, deleted_at, version)
        values (?, ?, 'Payback', ?, ?, '2026-07-01', ?, ?, now(), now(), ?, 0)
        """,
        UUID.randomUUID(),
        ownerId,
        Math.max(remainingMinor, 1),
        remainingMinor,
        state,
        position,
        deleted ? Timestamp.from(Instant.parse("2026-07-18T00:00:00Z")) : null);
  }

  private void insertFund(UUID ownerId, String state, long balanceMinor, int position) {
    UUID fundId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        insert into sinking_funds
          (id, owner_id, name, state, position, created_at, updated_at, version)
        values (?, ?, 'Savings', ?, ?, now(), now(), 0)
        """,
        fundId,
        ownerId,
        state,
        position);
    jdbcTemplate.update(
        """
        insert into sinking_fund_transactions
          (id, owner_id, sinking_fund_id, transaction_type, amount_minor, effective_date,
           created_at, updated_at, version)
        values (?, ?, ?, 'OPENING_BALANCE', ?, '2026-07-01', now(), now(), 0)
        """,
        UUID.randomUUID(),
        ownerId,
        fundId,
        balanceMinor);
  }

  private UUID insertLedger(
      UUID ownerId, String name, String state, boolean deleted, long totalMinor) {
    UUID ledgerId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-07-18T12:00:00Z"));
    jdbcTemplate.update(
        """
        insert into expense_ledgers
          (id, owner_id, name, state, finalized_at, settled_at, deleted_at, created_at, updated_at, version)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """,
        ledgerId,
        ownerId,
        name,
        state,
        state.equals("FINALIZED") ? now : null,
        state.equals("SETTLED") ? now : null,
        deleted ? now : null,
        now,
        now);
    if (totalMinor > 0) {
      jdbcTemplate.update(
          """
          insert into expense_ledger_items
            (id, owner_id, ledger_id, name, amount_minor, expense_date, created_at, updated_at, version)
          values (?, ?, ?, 'Item', ?, '2026-07-18', now(), now(), 0)
          """,
          UUID.randomUUID(),
          ownerId,
          ledgerId,
          totalMinor);
    }
    return ledgerId;
  }

  private JsonNode summary(String token) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(get("/api/v1/dashboard/summary").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private Owner register(String email, String timezone) throws Exception {
    JsonNode result =
        objectMapper.readTree(
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
                .getContentAsString());
    UUID ownerId =
        jdbcTemplate.queryForObject(
            "select id from user_accounts where email = ?", UUID.class, email);
    jdbcTemplate.update("update user_accounts set timezone = ? where id = ?", timezone, ownerId);
    return new Owner(ownerId, result.path("accessToken").asText());
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }

  private record Owner(UUID id, String token) {}

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(Instant.parse("2026-07-20T03:00:00Z"));
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
