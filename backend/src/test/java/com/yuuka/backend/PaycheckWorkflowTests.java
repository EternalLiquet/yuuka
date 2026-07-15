package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
class PaycheckWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createsExactCentPaycheckAndKeepsAllocatedButIncompleteWorkActive() throws Exception {
    String token = registerAndGetAccessToken("workflow@yuuka.local");

    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Free Paycheck",
                          "amountMinor": 197757,
                          "incomeDate": "2026-07-17"
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.allocatedMinor").value(0))
            .andExpect(jsonPath("$.unallocatedMinor").value(197757))
            .andReturn();

    JsonNode paycheck = objectMapper.readTree(created.getResponse().getContentAsString());
    String paycheckId = paycheck.path("id").asText();

    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/entries", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType": "SPENDING_BUCKET",
                      "name": "Groceries",
                      "amountMinor": 15000
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amountMinor").value(15000))
        .andExpect(jsonPath("$.status").value("NOT_PAID"));

    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allocatedMinor").value(15000))
        .andExpect(jsonPath("$.unallocatedMinor").value(182757));

    mockMvc
        .perform(get("/api/v1/paychecks/active").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(paycheckId));
  }

  @Test
  void keepsZeroAmountPaycheckWithNoEntriesActiveAndRejectsClosingIt() throws Exception {
    String token = registerAndGetAccessToken("empty@yuuka.local");

    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name": "Zero Check",
                          "amountMinor": 0,
                          "incomeDate": "2026-07-17"
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requiresAttention").value(true))
            .andReturn();

    JsonNode paycheck = objectMapper.readTree(created.getResponse().getContentAsString());
    String paycheckId = paycheck.path("id").asText();

    mockMvc
        .perform(get("/api/v1/paychecks/active").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(paycheckId));

    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/close", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + paycheck.path("version").asLong() + "}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
  }

  @Test
  void postingFinalOutstandingEntryAutomaticallyClosesPaycheckAndMovesItToHistory()
      throws Exception {
    String token = registerAndGetAccessToken("completed-active@yuuka.local");

    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Complete","amountMinor":15000,"incomeDate":"2026-07-17"}
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode paycheck = objectMapper.readTree(created.getResponse().getContentAsString());
    String paycheckId = paycheck.path("id").asText();

    MvcResult entryResult =
        mockMvc
            .perform(
                post("/api/v1/paychecks/{id}/entries", paycheckId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"entryType":"BILL","name":"Verizon","amountMinor":15000}
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode entry = objectMapper.readTree(entryResult.getResponse().getContentAsString());

    JsonNode posted =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/v1/entries/{id}/status", entry.path("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"toStatus":"POSTED","effectiveAt":"2026-07-17T12:00:00Z","version":%d}
                            """
                                .formatted(entry.path("version").asLong())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(posted.path("status").asText()).isEqualTo("POSTED");

    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CLOSED"))
        .andExpect(jsonPath("$.requiresAttention").value(false))
        .andExpect(jsonPath("$.closedAt").isString());
    assertThat(
            jdbcTemplate.queryForObject(
                "select state from paychecks where id = ?",
                String.class,
                UUID.fromString(paycheckId)))
        .isEqualTo("CLOSED");

    mockMvc
        .perform(get("/api/v1/paychecks/active").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(0));

    mockMvc
        .perform(get("/api/v1/paychecks/history").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(paycheckId))
        .andExpect(jsonPath("$.items[0].state").value("CLOSED"));
  }

  @Test
  void fullyPostedButUnderAllocatedPaycheckRemainsActive() throws Exception {
    String token = registerAndGetAccessToken("under-allocated-posted@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Under Allocated", 20000);
    JsonNode entry = addEntry(token, paycheck.path("id").asText(), "BILL", "Phone", 15000);

    changeStatus(token, entry, "POSTED");

    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", paycheck.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        .andExpect(jsonPath("$.unallocatedMinor").value(5000))
        .andExpect(jsonPath("$.requiresAttention").value(true));
    mockMvc
        .perform(get("/api/v1/paychecks/active").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));
  }

  @Test
  void fullyAllocatedPaycheckWithAnyNonPostedEntryRemainsActive() throws Exception {
    String token = registerAndGetAccessToken("allocated-processing@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Allocated Processing", 20000);
    JsonNode posted = addEntry(token, paycheck.path("id").asText(), "BILL", "Posted", 15000);
    JsonNode processing = addEntry(token, paycheck.path("id").asText(), "BILL", "Processing", 5000);

    changeStatus(token, posted, "POSTED");
    changeStatus(token, processing, "PROCESSING");

    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", paycheck.path("id").asText())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"))
        .andExpect(jsonPath("$.unallocatedMinor").value(0))
        .andExpect(jsonPath("$.requiresAttention").value(true));
    mockMvc
        .perform(get("/api/v1/paychecks/active").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));
  }

  @Test
  void reopenedCompletedPaycheckStaysActiveUntilExplicitCloseEvenAfterStatusMovesBackward()
      throws Exception {
    String token = registerAndGetAccessToken("reopened-lifecycle@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Reopened", 10000);
    String paycheckId = paycheck.path("id").asText();
    JsonNode entry = addEntry(token, paycheckId, "BILL", "Phone", 10000);
    JsonNode posted = changeStatus(token, entry, "POSTED");
    JsonNode completed = getPaycheck(token, paycheckId);
    assertThat(completed.path("state").asText()).isEqualTo("CLOSED");

    JsonNode reopened =
        json(
            post("/api/v1/paychecks/{id}/reopen", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + completed.path("version").asLong() + "}"),
            200);
    assertThat(reopened.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(reopened.path("reopenedAt").isTextual()).isTrue();

    JsonNode processing = changeStatus(token, posted, "PROCESSING");
    JsonNode reopenedProcessing = getPaycheck(token, paycheckId);
    assertThat(reopenedProcessing.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(reopenedProcessing.path("requiresAttention").asBoolean()).isTrue();

    JsonNode reposted = changeStatus(token, processing, "POSTED");
    assertThat(reposted.path("status").asText()).isEqualTo("POSTED");
    JsonNode stillActive = getPaycheck(token, paycheckId);
    assertThat(stillActive.path("state").asText()).isEqualTo("ACTIVE");
    assertThat(stillActive.path("requiresAttention").asBoolean()).isFalse();

    mockMvc
        .perform(get("/api/v1/paychecks/active").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(paycheckId));

    JsonNode reclosed =
        json(
            post("/api/v1/paychecks/{id}/close", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + stillActive.path("version").asLong() + "}"),
            200);
    assertThat(reclosed.path("state").asText()).isEqualTo("CLOSED");
  }

  @Test
  void classifiesBillPaymentMethodWithoutChangingStatusBehavior() throws Exception {
    String token = registerAndGetAccessToken("payment-method@yuuka.local");
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Payment Method","amountMinor":50000,"incomeDate":"2026-07-17"}
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    String paycheckId =
        objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

    MvcResult defaultBill =
        mockMvc
            .perform(
                post("/api/v1/paychecks/{id}/entries", paycheckId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"entryType":"BILL","name":"Autopay Bill","amountMinor":10000}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentMethod").value("AUTOPAY"))
            .andExpect(jsonPath("$.status").value("NOT_PAID"))
            .andReturn();

    MvcResult manualBill =
        mockMvc
            .perform(
                post("/api/v1/paychecks/{id}/entries", paycheckId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"entryType":"BILL","name":"Manual Bill","amountMinor":10000,"paymentMethod":"MANUAL"}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentMethod").value("MANUAL"))
            .andExpect(jsonPath("$.status").value("NOT_PAID"))
            .andReturn();
    JsonNode manual = objectMapper.readTree(manualBill.getResponse().getContentAsString());

    MvcResult paybackResult =
        mockMvc
            .perform(
                post("/api/v1/paybacks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name":"Loan",
                          "originalAmountMinor":20000,
                          "openingRemainingAmountMinor":20000,
                          "borrowedDate":"2026-07-12"
                        }
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    String paybackId =
        objectMapper.readTree(paybackResult.getResponse().getContentAsString()).path("id").asText();

    MvcResult unrelatedUpdate =
        mockMvc
            .perform(
                patch("/api/v1/entries/{id}", manual.path("id").asText())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "entryType":"BILL",
                          "name":"Manual Bill Updated",
                          "amountMinor":11000,
                          "dueDate":"2026-07-20",
                          "accountName":"Checking",
                          "payee":"Utility Co",
                          "notes":"Pay this directly",
                          "paybackId":"%s",
                          "version":%d
                        }
                        """
                            .formatted(paybackId, manual.path("version").asLong())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethod").value("MANUAL"))
            .andExpect(jsonPath("$.name").value("Manual Bill Updated"))
            .andExpect(jsonPath("$.paybackId").value(paybackId))
            .andReturn();
    manual = objectMapper.readTree(unrelatedUpdate.getResponse().getContentAsString());
    assertThat(
            jdbcTemplate.queryForObject(
                "select payment_method from paycheck_entries where id = ?",
                String.class,
                UUID.fromString(manual.path("id").asText())))
        .isEqualTo("MANUAL");

    MvcResult explicitAutopayUpdate =
        mockMvc
            .perform(
                patch("/api/v1/entries/{id}", manual.path("id").asText())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "entryType":"BILL",
                          "name":"Manual Bill Updated",
                          "amountMinor":11000,
                          "paymentMethod":"AUTOPAY",
                          "dueDate":"2026-07-20",
                          "accountName":"Checking",
                          "payee":"Utility Co",
                          "notes":"Pay automatically",
                          "paybackId":"%s",
                          "version":%d
                        }
                        """
                            .formatted(paybackId, manual.path("version").asLong())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethod").value("AUTOPAY"))
            .andReturn();

    manual = objectMapper.readTree(explicitAutopayUpdate.getResponse().getContentAsString());

    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/entries", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"SPENDING_BUCKET","name":"Invalid","amountMinor":1000,"paymentMethod":"MANUAL"}
                    """))
        .andExpect(status().isUnprocessableEntity());

    MvcResult changedToBucket =
        mockMvc
            .perform(
                patch("/api/v1/entries/{id}", manual.path("id").asText())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"entryType":"SPENDING_BUCKET","name":"Bucket Now","amountMinor":10000,"version":%d}
                        """
                            .formatted(manual.path("version").asLong())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethod").value(nullValue()))
            .andReturn();
    JsonNode bucket = objectMapper.readTree(changedToBucket.getResponse().getContentAsString());

    mockMvc
        .perform(
            patch("/api/v1/entries/{id}", bucket.path("id").asText())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"BILL","name":"Bill Again","amountMinor":10000,"version":%d}
                    """
                        .formatted(bucket.path("version").asLong())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentMethod").value("AUTOPAY"))
        .andExpect(jsonPath("$.status").value("NOT_PAID"));

    JsonNode autopay = objectMapper.readTree(defaultBill.getResponse().getContentAsString());
    mockMvc
        .perform(
            post("/api/v1/entries/{id}/status", autopay.path("id").asText())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"toStatus":"PROCESSING","effectiveAt":"2026-07-17T12:00:00Z","version":%d}
                    """
                        .formatted(autopay.path("version").asLong())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentMethod").value("AUTOPAY"))
        .andExpect(jsonPath("$.status").value("PROCESSING"));

    JsonNode detail =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/v1/paychecks/{id}", paycheckId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/leftover-entry", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paycheckVersion\":" + detail.path("version").asLong() + "}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.entryType").value("BILL"))
        .andExpect(jsonPath("$.paymentMethod").value("AUTOPAY"));
  }

  @Test
  void migrationAddsBillPaymentMethodColumnsAndConstraints() {
    Integer columnCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from information_schema.columns
            where table_schema = current_schema()
              and table_name in ('paycheck_entries', 'template_entries')
              and column_name = 'payment_method'
            """,
            Integer.class);
    Integer constraintCount =
        jdbcTemplate.queryForObject(
            """
            select count(*)
            from information_schema.table_constraints
            where table_schema = current_schema()
              and constraint_name in (
                'chk_paycheck_entry_payment_method_value',
                'chk_paycheck_entry_payment_method_type',
                'chk_template_entry_payment_method_value',
                'chk_template_entry_payment_method_type'
              )
            """,
            Integer.class);

    assertThat(columnCount).isEqualTo(2);
    assertThat(constraintCount).isEqualTo(4);
  }

  @Test
  void overAllocationErrorsUseStructuredMoneyDetailsWithoutInternalTerminology() throws Exception {
    assertOverAllocationError("over-98@yuuka.local", 100, 198, 98);
    assertOverAllocationError("over-150@yuuka.local", 100, 250, 150);
  }

  @Test
  void rejectsMissingMoneyAmountsInsteadOfDefaultingToZero() throws Exception {
    String token = registerAndGetAccessToken("amount-required@yuuka.local");

    mockMvc
        .perform(
            post("/api/v1/paychecks")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Missing amount","incomeDate":"2026-07-17"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());

    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"With amount","amountMinor":10000,"incomeDate":"2026-07-17"}
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    String paycheckId =
        objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/entries", paycheckId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"BILL","name":"Missing amount"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.amountMinor").exists());
  }

  @Test
  void rejectsCrossOwnerAccessWithoutLeakingThePaycheck() throws Exception {
    String ownerToken = registerAndGetAccessToken("owner-a@yuuka.local");
    String otherToken = registerAndGetAccessToken("owner-b@yuuka.local");

    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + ownerToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Private","amountMinor":10000,"incomeDate":"2026-07-10"}
                        """))
            .andExpect(status().isCreated())
            .andReturn();
    String id =
        objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

    mockMvc
        .perform(get("/api/v1/paychecks/{id}", id).header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  private void assertOverAllocationError(
      String email, long paycheckAmountMinor, long entryAmountMinor, long expectedOverageMinor)
      throws Exception {
    String token = registerAndGetAccessToken(email);
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/paychecks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"Over allocation","amountMinor":%d,"incomeDate":"2026-07-17"}
                        """
                            .formatted(paycheckAmountMinor)))
            .andExpect(status().isCreated())
            .andReturn();
    String paycheckId =
        objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paychecks/{id}/entries", paycheckId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"entryType":"BILL","name":"Too much","amountMinor":%d}
                        """
                            .formatted(entryAmountMinor)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("PAYCHECK_OVER_ALLOCATED"))
            .andExpect(jsonPath("$.message").value("This would over-allocate the paycheck."))
            .andExpect(jsonPath("$.details.amountMinor").value(expectedOverageMinor))
            .andExpect(jsonPath("$.details.currencyCode").value("USD"))
            .andReturn();

    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("minor unit");
    assertThat(body).doesNotContain("amountMinor must");
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

  private JsonNode createPaycheck(String token, String name, long amountMinor) throws Exception {
    return json(
        post("/api/v1/paychecks")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"%s","amountMinor":%d,"incomeDate":"2026-07-17"}
                """
                    .formatted(name, amountMinor)),
        201);
  }

  private JsonNode addEntry(
      String token, String paycheckId, String entryType, String name, long amountMinor)
      throws Exception {
    return json(
        post("/api/v1/paychecks/{id}/entries", paycheckId)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"entryType":"%s","name":"%s","amountMinor":%d}
                """
                    .formatted(entryType, name, amountMinor)),
        201);
  }

  private JsonNode changeStatus(String token, JsonNode entry, String nextStatus) throws Exception {
    return json(
        post("/api/v1/entries/{id}/status", entry.path("id").asText())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"%s","effectiveAt":"2026-07-17T12:00:00Z","version":%d}
                """
                    .formatted(nextStatus, entry.path("version").asLong())),
        200);
  }

  private JsonNode getPaycheck(String token, String paycheckId) throws Exception {
    return json(
        get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", "Bearer " + token), 200);
  }

  private JsonNode json(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
  }
}
