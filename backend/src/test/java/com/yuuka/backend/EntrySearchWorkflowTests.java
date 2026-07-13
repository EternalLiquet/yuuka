package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class EntrySearchWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void searchesEntriesByExactAndPartialNameWithDeterministicOrdering() throws Exception {
    String token = registerAndGetAccessToken("entry-search-name@yuuka.local");
    String olderPaycheck = createPaycheck(token, "Utilities", 100000, "2026-07-05");
    String newerPaycheck = createPaycheck(token, "Subscriptions", 100000, "2026-07-12");
    String newestPaycheck = createPaycheck(token, "Late Month", 100000, "2026-07-19");

    String exact = addEntry(token, olderPaycheck, "Netflix", "BILL", 1399).id();
    String partialNewest = addEntry(token, newestPaycheck, "Backup Netflix", "BILL", 1399).id();
    addEntry(token, newerPaycheck, "my netflix plan", "SINKING_FUND", 2400);

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "nEtFlIx")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.items[0].entryId").value(exact))
        .andExpect(jsonPath("$.items[0].kind").value("PAYCHECK_ENTRY"))
        .andExpect(jsonPath("$.items[0].entryName").value("Netflix"))
        .andExpect(jsonPath("$.items[0].entryType").value("BILL"))
        .andExpect(jsonPath("$.items[0].status").value("NOT_PAID"))
        .andExpect(jsonPath("$.items[0].paycheckName").value("Utilities"))
        .andExpect(jsonPath("$.items[0].paycheckIncomeDate").value("2026-07-05"))
        .andExpect(jsonPath("$.items[0].paycheckContext").value("ACTIVE"))
        .andExpect(jsonPath("$.items[1].entryId").value(partialNewest));
  }

  @Test
  void treatsLikeWildcardAndEscapeCharactersAsLiteralNameText() throws Exception {
    String token = registerAndGetAccessToken("entry-search-literals@yuuka.local");
    String otherToken = registerAndGetAccessToken("entry-search-literals-other@yuuka.local");
    String paycheck = createPaycheck(token, "Literal Match Check", 100000, "2026-07-12");
    String otherPaycheck = createPaycheck(otherToken, "Literal Match Check", 100000, "2026-07-12");
    addEntry(token, paycheck, "Save 100%", "BILL", 1000);
    addEntry(token, paycheck, "Save 1000", "BILL", 1000);
    addEntry(token, paycheck, "under_score_bill", "BILL", 1000);
    addEntry(token, paycheck, "underXscore bill", "BILL", 1000);
    addEntry(token, paycheck, "bang!escape", "BILL", 1000);
    addEntry(otherToken, otherPaycheck, "Save 100%", "BILL", 1000);

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "100%")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].entryName").value("Save 100%"));

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "UNDER_score")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].entryName").value("under_score_bill"));

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "bang!")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].entryName").value("bang!escape"));
  }

  @Test
  void searchesExactAmountAndReturnsDuplicateAmounts() throws Exception {
    String token = registerAndGetAccessToken("entry-search-amount@yuuka.local");
    String paycheck = createPaycheck(token, "Paycheck", 100000, "2026-07-12");
    addEntry(token, paycheck, "Netflix", "BILL", 1399);
    addEntry(token, paycheck, "Hulu", "BILL", 1399);
    addEntry(token, paycheck, "Groceries", "SPENDING_BUCKET", 15000);

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("amountMinor", "1399")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.items[0].amountMinor").value(1399))
        .andExpect(jsonPath("$.items[1].amountMinor").value(1399));
  }

  @Test
  void appliesVisibilityPolicyForScopesContextAndPagination() throws Exception {
    String token = registerAndGetAccessToken("entry-search-scope@yuuka.local");
    String activeUnallocated =
        createPaycheck(token, "Active Unallocated Paycheck", 100000, "2026-07-18");
    String activeOpenEntry =
        createPaycheck(token, "Active Open Entry Paycheck", 100000, "2026-07-17");
    String completedActive =
        createPaycheck(token, "Completed Active Paycheck", 100000, "2026-07-16");
    String reopenedCompleted =
        createPaycheck(token, "Reopened Completed Paycheck", 100000, "2026-07-15");
    String closedPaycheck = createPaycheck(token, "Closed Paycheck", 100000, "2026-07-14");
    String archivedPaycheck = createPaycheck(token, "Archived Paycheck", 100000, "2026-07-13");

    addEntry(token, activeUnallocated, "Internet Policy Match", "BILL", 70000);
    EntryFixture openEntry =
        addEntry(token, activeOpenEntry, "Internet Policy Match", "BILL", 100000);
    changeStatus(token, openEntry, "PROCESSING");

    EntryFixture completedEntry =
        addEntry(token, completedActive, "Internet Policy Match", "BILL", 100000);
    changeStatus(token, completedEntry, "POSTED");

    EntryFixture reopenedEntry =
        addEntry(token, reopenedCompleted, "Internet Policy Match", "BILL", 100000);
    changeStatus(token, reopenedEntry, "POSTED");
    closePaycheck(token, reopenedCompleted);
    reopenPaycheck(token, reopenedCompleted);

    EntryFixture closedEntry =
        addEntry(token, closedPaycheck, "Internet Policy Match", "BILL", 100000);
    changeStatus(token, closedEntry, "POSTED");
    closePaycheck(token, closedPaycheck);

    EntryFixture archivedEntry =
        addEntry(token, archivedPaycheck, "Internet Policy Match", "BILL", 100000);
    changeStatus(token, archivedEntry, "POSTED");
    archivePaycheck(token, archivedPaycheck);

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "internet policy")
                .param("scope", "ACTIVE")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.items[0].paycheckName").value("Active Unallocated Paycheck"))
        .andExpect(jsonPath("$.items[0].paycheckContext").value("ACTIVE"))
        .andExpect(jsonPath("$.items[1].paycheckName").value("Active Open Entry Paycheck"))
        .andExpect(jsonPath("$.items[1].paycheckContext").value("ACTIVE"))
        .andExpect(jsonPath("$.items[2].paycheckName").value("Reopened Completed Paycheck"))
        .andExpect(jsonPath("$.items[2].paycheckContext").value("ACTIVE"));

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "internet policy")
                .param("scope", "HISTORY")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3))
        .andExpect(jsonPath("$.items[0].paycheckName").value("Completed Active Paycheck"))
        .andExpect(jsonPath("$.items[0].paycheckContext").value("HISTORY"))
        .andExpect(jsonPath("$.items[1].paycheckName").value("Closed Paycheck"))
        .andExpect(jsonPath("$.items[1].paycheckContext").value("HISTORY"))
        .andExpect(jsonPath("$.items[2].paycheckName").value("Archived Paycheck"))
        .andExpect(jsonPath("$.items[2].paycheckContext").value("HISTORY"));

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "internet policy")
                .param("scope", "ALL")
                .param("page", "0")
                .param("size", "4")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4))
        .andExpect(jsonPath("$.totalItems").value(6))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.items[0].paycheckContext").value("ACTIVE"))
        .andExpect(jsonPath("$.items[1].paycheckContext").value("ACTIVE"))
        .andExpect(jsonPath("$.items[2].paycheckContext").value("HISTORY"))
        .andExpect(jsonPath("$.items[3].paycheckContext").value("ACTIVE"));

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "internet policy")
                .param("scope", "ALL")
                .param("page", "1")
                .param("size", "4")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.totalItems").value(6))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void excludesSoftDeletedEntriesAndIsolatesOwnersWithoutLeakingCounts() throws Exception {
    String ownerToken = registerAndGetAccessToken("entry-search-owner@yuuka.local");
    String otherToken = registerAndGetAccessToken("entry-search-other@yuuka.local");
    String paycheck = createPaycheck(ownerToken, "Owner Paycheck", 100000, "2026-07-12");
    EntryFixture deleted = addEntry(ownerToken, paycheck, "Private Netflix", "BILL", 1399);
    addEntry(ownerToken, paycheck, "Visible Netflix", "BILL", 1399);
    deleteEntry(ownerToken, deleted);

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "netflix")
                .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].entryName").value("Visible Netflix"));

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "netflix")
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalItems").value(0))
        .andExpect(jsonPath("$.totalPages").value(0))
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void includesPaybackLinkedEntriesAndPaycheckNameMatches() throws Exception {
    String token = registerAndGetAccessToken("entry-search-payback@yuuka.local");
    String paycheck = createPaycheck(token, "July Payback Check", 100000, "2026-07-12");
    String paybackId = createPayback(token, "Mom Loan");
    addEntryWithPayback(token, paycheck, "Repayment", "BILL", 2500, paybackId);

    mockMvc
        .perform(
            get("/api/v1/search/entries")
                .param("query", "payback check")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1))
        .andExpect(jsonPath("$.items[0].entryName").value("Repayment"));
  }

  @Test
  void rejectsRequestsWithoutSearchCriteria() throws Exception {
    String token = registerAndGetAccessToken("entry-search-empty@yuuka.local");

    mockMvc
        .perform(get("/api/v1/search/entries").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SEARCH_CRITERIA_REQUIRED"));
  }

  @Test
  void migrationAddsEntrySearchIndexes() {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from pg_indexes
            where schemaname = current_schema()
              and indexname in (
                'idx_paycheck_entries_owner_amount_live',
                'idx_paycheck_entries_owner_name_live',
                'idx_paychecks_owner_name_income'
              )
            """,
            Integer.class);

    assertThat(count).isEqualTo(3);
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

  private String createPaycheck(String token, String name, long amountMinor, String incomeDate)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"%s","amountMinor":%d,"incomeDate":"%s"}
                        """
                            .formatted(name, amountMinor, incomeDate)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
  }

  private EntryFixture addEntry(
      String token, String paycheckId, String name, String entryType, long amountMinor)
      throws Exception {
    return addEntryWithPayback(token, paycheckId, name, entryType, amountMinor, null);
  }

  private EntryFixture addEntryWithPayback(
      String token,
      String paycheckId,
      String name,
      String entryType,
      long amountMinor,
      String paybackId)
      throws Exception {
    String paybackField = paybackId == null ? "" : ",\"paybackId\":\"%s\"".formatted(paybackId);
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paychecks/{id}/entries", paycheckId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"entryType":"%s","name":"%s","amountMinor":%d%s}
                        """
                            .formatted(entryType, name, amountMinor, paybackField)))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode entry = objectMapper.readTree(result.getResponse().getContentAsString());
    return new EntryFixture(entry.path("id").asText(), entry.path("version").asLong());
  }

  private String createPayback(String token, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paybacks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name":"%s",
                          "originalAmountMinor":5000,
                          "openingRemainingAmountMinor":5000,
                          "borrowedDate":"2026-07-01"
                        }
                        """
                            .formatted(name)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
  }

  private void archivePaycheck(String token, String paycheckId) throws Exception {
    JsonNode paycheck = getPaycheck(token, paycheckId);
    mockMvc
        .perform(
            delete("/api/v1/paychecks/{id}", paycheckId)
                .param("version", paycheck.path("version").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  private void closePaycheck(String token, String paycheckId) throws Exception {
    JsonNode paycheck = getPaycheck(token, paycheckId);
    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/close", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"version":%d}
                    """
                        .formatted(paycheck.path("version").asLong())))
        .andExpect(status().isOk());
  }

  private void reopenPaycheck(String token, String paycheckId) throws Exception {
    JsonNode paycheck = getPaycheck(token, paycheckId);
    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/reopen", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"version":%d}
                    """
                        .formatted(paycheck.path("version").asLong())))
        .andExpect(status().isOk());
  }

  private EntryFixture changeStatus(String token, EntryFixture entry, String nextStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/entries/{id}/status", entry.id())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"toStatus":"%s","effectiveAt":"2026-07-17T12:00:00Z","version":%d}
                        """
                            .formatted(nextStatus, entry.version())))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode changed = objectMapper.readTree(result.getResponse().getContentAsString());
    return new EntryFixture(changed.path("id").asText(), changed.path("version").asLong());
  }

  private JsonNode getPaycheck(String token, String paycheckId) throws Exception {
    JsonNode paycheck =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/v1/paychecks/{id}", paycheckId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return paycheck;
  }

  private void deleteEntry(String token, EntryFixture entry) throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/entries/{id}", entry.id())
                .param("version", String.valueOf(entry.version()))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
  }

  private record EntryFixture(String id, long version) {}
}
