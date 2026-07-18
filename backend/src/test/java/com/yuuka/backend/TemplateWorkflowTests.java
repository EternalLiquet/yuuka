package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class TemplateWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void managesTemplatesEntriesReorderAndSoftDeleteWithStaleWriteProtection() throws Exception {
    String token = registerAndGetAccessToken("templates-crud@yuuka.local");
    JsonNode template = createTemplate(token, "Rent 1", 120000);
    String templateId = template.path("id").asText();
    JsonNode bill = template.path("entries").get(0);
    JsonNode bucket = template.path("entries").get(1);

    mockMvc
        .perform(get("/api/v1/templates").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("Rent 1"))
        .andExpect(jsonPath("$.items[0].entryCount").value(2))
        .andExpect(jsonPath("$.items[0].defaultTotalMinor").value(120000));

    JsonNode renamed =
        updateTemplate(token, templateId, "Rent 1 Updated", template.path("version").asLong(), 200);
    updateTemplate(token, templateId, "Stale", template.path("version").asLong(), 409);

    JsonNode sinking =
        addTemplateEntry(
            token,
            templateId,
            """
            {
              "entryType":"SINKING_FUND",
              "name":"Car tags",
              "defaultAmountMinor":10000,
              "targetMinor":120000,
              "targetDate":"2026-12-01"
            }
            """);
    JsonNode updatedBill =
        updateTemplateEntry(
            token,
            bill.path("id").asText(),
            """
            {
              "entryType":"BILL",
              "name":"Rent Manual",
              "defaultAmountMinor":85000,
              "paymentMethod":"MANUAL",
              "defaultDueOffsetDays":2,
              "version":%d
            }
            """
                .formatted(bill.path("version").asLong()),
            200);
    assertThat(updatedBill.path("paymentMethod").asText()).isEqualTo("MANUAL");

    JsonNode current = getTemplate(token, templateId);
    JsonNode reordered =
        reorderTemplate(
            token,
            templateId,
            List.of(
                sinking.path("id").asText(), bucket.path("id").asText(), bill.path("id").asText()),
            current.path("version").asLong(),
            200);
    assertThat(reordered.path("entries").get(0).path("id").asText())
        .isEqualTo(sinking.path("id").asText());
    assertThat(positionForTemplateEntry(UUID.fromString(sinking.path("id").asText()))).isZero();

    reorderTemplate(
        token,
        templateId,
        List.of(sinking.path("id").asText(), sinking.path("id").asText(), bill.path("id").asText()),
        reordered.path("version").asLong(),
        422);
    reorderTemplate(
        token,
        templateId,
        List.of(sinking.path("id").asText(), bucket.path("id").asText(), bill.path("id").asText()),
        current.path("version").asLong(),
        409);

    deleteTemplateEntry(token, bucket.path("id").asText(), bucket.path("version").asLong(), 409);
    JsonNode bucketAfterReorder =
        entryById(getTemplate(token, templateId), bucket.path("id").asText());
    deleteTemplateEntry(
        token,
        bucketAfterReorder.path("id").asText(),
        bucketAfterReorder.path("version").asLong(),
        204);
    JsonNode afterDelete = getTemplate(token, templateId);
    assertThat(afterDelete.path("entryCount").asInt()).isEqualTo(2);

    archiveTemplate(token, templateId, renamed.path("version").asLong(), 409);
    JsonNode archived =
        archiveTemplate(token, templateId, afterDelete.path("version").asLong(), 200);
    assertThat(archived.path("archived").asBoolean()).isTrue();
    assertThat(auditCount("TEMPLATE", UUID.fromString(templateId), "ARCHIVED")).isEqualTo(1);
  }

  @Test
  void protectsTemplatesAndTemplateEntriesByOwner() throws Exception {
    String ownerToken = registerAndGetAccessToken("templates-owner@yuuka.local");
    String otherToken = registerAndGetAccessToken("templates-other@yuuka.local");
    JsonNode template = createTemplate(ownerToken, "Private", 50000);
    String templateId = template.path("id").asText();
    String entryId = template.path("entries").get(0).path("id").asText();

    mockMvc
        .perform(
            get("/api/v1/templates/{id}", templateId)
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    updateTemplateEntry(
        otherToken,
        entryId,
        """
        {"entryType":"BILL","name":"Other","defaultAmountMinor":1000,"version":0}
        """,
        404);
    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "name":"Other",
                      "amountMinor":50000,
                      "incomeDate":"2026-07-17"
                    }
                    """
                        .formatted(templateId)))
        .andExpect(status().isNotFound());
  }

  @Test
  void templateDefaultTotalOverflowUsesBusinessRuleEnvelope() throws Exception {
    String token = registerAndGetAccessToken("templates-overflow@yuuka.local");
    long templateCountBefore = tableCount("templates");
    long templateEntryCountBefore = tableCount("template_entries");
    long createdAuditCountBefore = templateCreationAuditCount();

    mockMvc
        .perform(
            post("/api/v1/templates")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Huge Template",
                      "entries":[
                        {"entryType":"BILL","name":"Huge Bill","defaultAmountMinor":%d},
                        {"entryType":"SPENDING_BUCKET","name":"Huge Bucket","defaultAmountMinor":1}
                      ]
                    }
                    """
                        .formatted(Long.MAX_VALUE)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("MONEY_AMOUNT_OVERFLOW"))
        .andExpect(jsonPath("$.details.currencyCode").value("USD"));

    assertThat(tableCount("templates")).isEqualTo(templateCountBefore);
    assertThat(tableCount("template_entries")).isEqualTo(templateEntryCountBefore);
    assertThat(templateCreationAuditCount()).isEqualTo(createdAuditCountBefore);
  }

  @Test
  void appliesTemplateTransactionallyAndCreatesIndependentOrderedSnapshots() throws Exception {
    String token = registerAndGetAccessToken("templates-apply@yuuka.local");
    JsonNode template = createTemplate(token, "Utilities", 70000);
    String templateId = template.path("id").asText();
    String firstEntryId = template.path("entries").get(0).path("id").asText();
    long paycheckCountBefore = paycheckCount();

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "name":"Too Small",
                      "amountMinor":69999,
                      "incomeDate":"2026-07-17"
                    }
                    """
                        .formatted(templateId)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("PAYCHECK_OVER_ALLOCATED"));
    assertThat(paycheckCount()).isEqualTo(paycheckCountBefore);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paychecks/from-template")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "templateId":"%s",
                          "name":"July Utilities",
                          "amountMinor":70000,
                          "incomeDate":"2026-07-17",
                          "source":"Work"
                        }
                        """
                            .formatted(templateId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.templateSourceId").value(templateId))
            .andExpect(jsonPath("$.entries[0].name").value("Rent"))
            .andExpect(jsonPath("$.entries[0].paymentMethod").value("MANUAL"))
            .andExpect(jsonPath("$.entries[1].name").value("Groceries"))
            .andExpect(jsonPath("$.entries[1].paymentMethod").value(nullValue()))
            .andReturn();
    JsonNode paycheck = objectMapper.readTree(result.getResponse().getContentAsString());
    String paycheckId = paycheck.path("id").asText();
    String copiedEntryId = paycheck.path("entries").get(0).path("id").asText();
    assertThat(copiedEntryId).isNotEqualTo(firstEntryId);

    updateTemplateEntry(
        token,
        firstEntryId,
        """
        {
          "entryType":"BILL",
          "name":"Rent Changed",
          "defaultAmountMinor":60000,
          "paymentMethod":"AUTOPAY",
          "version":%d
        }
        """
            .formatted(template.path("entries").get(0).path("version").asLong()),
        200);

    JsonNode refreshedPaycheck = getPaycheck(token, paycheckId);
    assertThat(refreshedPaycheck.path("entries").get(0).path("name").asText()).isEqualTo("Rent");
    assertThat(refreshedPaycheck.path("entries").get(0).path("paymentMethod").asText())
        .isEqualTo("MANUAL");

    mockMvc
        .perform(
            patch("/api/v1/entries/{id}", copiedEntryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"BILL",
                      "name":"Paycheck Rent",
                      "amountMinor":50000,
                      "paymentMethod":"MANUAL",
                      "version":%d
                    }
                    """
                        .formatted(
                            refreshedPaycheck.path("entries").get(0).path("version").asLong())))
        .andExpect(status().isOk());
    JsonNode refreshedTemplate = getTemplate(token, templateId);
    assertThat(refreshedTemplate.path("entries").get(0).path("name").asText())
        .isEqualTo("Rent Changed");
  }

  @Test
  void appliesTemplateWithOrderedOverridesWithoutMutatingSourceTemplate() throws Exception {
    String token = registerAndGetAccessToken("templates-overrides@yuuka.local");
    JsonNode template = createTemplate(token, "Override Source", 120000);
    String templateId = template.path("id").asText();

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "name":"Edited Paycheck",
                      "amountMinor":95000,
                      "incomeDate":"2026-07-17",
                      "entries":[
                        {
                          "entryType":"SPENDING_BUCKET",
                          "name":"Fuel",
                          "amountMinor":5000
                        },
                        {
                          "entryType":"BILL",
                          "name":"Rent Adjusted",
                          "amountMinor":90000,
                          "paymentMethod":"AUTOPAY",
                          "dueDate":"2026-07-19",
                          "accountName":"Checking",
                          "payee":"Landlord"
                        }
                      ]
                    }
                    """
                        .formatted(templateId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.templateSourceId").value(templateId))
        .andExpect(jsonPath("$.entries", hasSize(2)))
        .andExpect(jsonPath("$.entries[0].name").value("Fuel"))
        .andExpect(jsonPath("$.entries[0].paymentMethod").value(nullValue()))
        .andExpect(jsonPath("$.entries[1].name").value("Rent Adjusted"))
        .andExpect(jsonPath("$.entries[1].paymentMethod").value("AUTOPAY"))
        .andExpect(jsonPath("$.entries[1].dueDate").value("2026-07-19"));

    JsonNode refreshedTemplate = getTemplate(token, templateId);
    assertThat(refreshedTemplate.path("entries").get(0).path("name").asText()).isEqualTo("Rent");
    assertThat(refreshedTemplate.path("entries").get(0).path("defaultAmountMinor").asLong())
        .isEqualTo(110000);
    assertThat(refreshedTemplate.path("entries")).hasSize(2);
  }

  @Test
  void appliesTemplateOverridesWithSinkingFundAssignments() throws Exception {
    String token = registerAndGetAccessToken("templates-sinking-fund@yuuka.local");
    JsonNode template = createTemplate(token, "Fund Overrides", 120000);
    JsonNode fund = createSinkingFund(token, "Car tags");

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "name":"Funded Paycheck",
                      "amountMinor":30000,
                      "incomeDate":"2026-07-17",
                      "entries":[
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Car tags",
                          "amountMinor":10000,
                          "targetMinor":120000,
                          "targetDate":"2026-12-01",
                          "sinkingFundId":"%s"
                        },
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Car insurance",
                          "amountMinor":20000,
                          "sinkingFundId":"%s"
                        }
                      ]
                    }
                    """
                        .formatted(
                            template.path("id").asText(),
                            fund.path("id").asText(),
                            fund.path("id").asText())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.entries", hasSize(2)))
        .andExpect(jsonPath("$.entries[0].sinkingFundId").value(fund.path("id").asText()))
        .andExpect(jsonPath("$.entries[0].targetMinor").value(nullValue()))
        .andExpect(jsonPath("$.entries[0].targetDate").value(nullValue()))
        .andExpect(jsonPath("$.entries[0].status").value("NOT_PAID"))
        .andExpect(jsonPath("$.entries[1].sinkingFundId").value(fund.path("id").asText()))
        .andExpect(jsonPath("$.entries[1].status").value("NOT_PAID"));

    JsonNode refreshedFund = getSinkingFund(token, fund.path("id").asText());
    assertThat(refreshedFund.path("currentBalanceMinor").asLong()).isZero();
    assertThat(refreshedFund.path("transactionCount").asLong()).isZero();
  }

  @Test
  void rejectsTemplateSinkingFundAssignmentsTransactionally() throws Exception {
    String ownerToken = registerAndGetAccessToken("templates-sinking-owner@yuuka.local");
    String otherToken = registerAndGetAccessToken("templates-sinking-other@yuuka.local");
    JsonNode template = createTemplate(ownerToken, "Assignment Source", 120000);
    JsonNode ownerFund = createSinkingFund(ownerToken, "Owner Fund");
    JsonNode archivedFund = createSinkingFund(ownerToken, "Archived Fund");
    archiveSinkingFund(
        ownerToken, archivedFund.path("id").asText(), archivedFund.path("version").asLong());
    JsonNode otherFund = createSinkingFund(otherToken, "Other Fund");
    long paycheckCountBefore = tableCount("paychecks");
    long entryCountBefore = tableCount("paycheck_entries");
    long statusCountBefore = tableCount("entry_status_events");
    long auditCountBefore = tableCount("audit_events");

    JsonNode crossOwnerError =
        postTemplateAssignment(
            ownerToken, template.path("id").asText(), otherFund.path("id").asText(), 1000, 404);
    assertThat(crossOwnerError.path("code").asText()).isEqualTo("NOT_FOUND");
    assertPaycheckApplicationCounts(
        paycheckCountBefore, entryCountBefore, statusCountBefore, auditCountBefore);

    JsonNode archivedError =
        postTemplateAssignment(
            ownerToken, template.path("id").asText(), archivedFund.path("id").asText(), 1000, 422);
    assertThat(archivedError.path("code").asText()).isEqualTo("SINKING_FUND_NOT_ACTIVE");
    assertPaycheckApplicationCounts(
        paycheckCountBefore, entryCountBefore, statusCountBefore, auditCountBefore);

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "name":"Zero Fund",
                      "amountMinor":1000,
                      "incomeDate":"2026-07-17",
                      "entries":[
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Zero",
                          "amountMinor":0,
                          "sinkingFundId":"%s"
                        }
                      ]
                    }
                    """
                        .formatted(template.path("id").asText(), ownerFund.path("id").asText())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("SINKING_FUND_CONTRIBUTION_AMOUNT_REQUIRED"));
    assertPaycheckApplicationCounts(
        paycheckCountBefore, entryCountBefore, statusCountBefore, auditCountBefore);
  }

  @Test
  void rejectsTemplateApplicationWithMultipleBalanceAssignmentsTransactionally() throws Exception {
    String token = registerAndGetAccessToken("templates-exclusive-assignments@yuuka.local");
    JsonNode template = createTemplate(token, "Exclusive Source", 120000);
    JsonNode fund = createSinkingFund(token, "Exclusive Fund");
    JsonNode payback = createPayback(token, "Exclusive Loan", 5000);
    long paycheckCountBefore = tableCount("paychecks");
    long entryCountBefore = tableCount("paycheck_entries");
    long statusCountBefore = tableCount("entry_status_events");
    long auditCountBefore = tableCount("audit_events");

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "name":"Bad Assignment",
                      "amountMinor":1000,
                      "incomeDate":"2026-07-17",
                      "entries":[
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Both",
                          "amountMinor":1000,
                          "paybackId":"%s",
                          "sinkingFundId":"%s"
                        }
                      ]
                    }
                    """
                        .formatted(
                            template.path("id").asText(),
                            payback.path("id").asText(),
                            fund.path("id").asText())))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ENTRY_MULTIPLE_BALANCE_ASSIGNMENTS"));

    assertPaycheckApplicationCounts(
        paycheckCountBefore, entryCountBefore, statusCountBefore, auditCountBefore);
  }

  private JsonNode createTemplate(String token, String name, long totalMinor) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/templates")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name":"%s",
                          "description":"Repeat budget",
                          "entries":[
                            {
                              "entryType":"BILL",
                              "name":"Rent",
                              "defaultAmountMinor":%d,
                              "paymentMethod":"MANUAL"
                            },
                            {
                              "entryType":"SPENDING_BUCKET",
                              "name":"Groceries",
                              "defaultAmountMinor":%d
                            }
                          ]
                        }
                        """
                            .formatted(name, totalMinor - 10000, 10000)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode getTemplate(String token, String templateId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/templates/{id}", templateId)
                    .header("Authorization", "Bearer " + token))
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

  private JsonNode createSinkingFund(String token, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/sinking-funds")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name":"%s",
                          "targetMinor":null,
                          "targetDate":null,
                          "notes":null,
                          "openingBalanceMinor":null
                        }
                        """
                            .formatted(name)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode getSinkingFund(String token, String fundId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/sinking-funds/{id}", fundId)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode createPayback(String token, String name, long openingRemainingAmountMinor)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paybacks")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "name":%s,
                          "originalAmountMinor":%d,
                          "openingRemainingAmountMinor":%d,
                          "borrowedDate":"2026-07-01"
                        }
                        """
                            .formatted(
                                objectMapper.writeValueAsString(name),
                                openingRemainingAmountMinor,
                                openingRemainingAmountMinor)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private void archiveSinkingFund(String token, String fundId, long version) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sinking-funds/{id}/archive", fundId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + ",\"confirmPositiveBalance\":false}"))
        .andExpect(status().isOk());
  }

  private JsonNode postTemplateAssignment(
      String token, String templateId, String sinkingFundId, long amountMinor, int expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/paychecks/from-template")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                    {
                      "templateId":"%s",
                      "name":"Rejected Fund",
                      "amountMinor":1000,
                      "incomeDate":"2026-07-17",
                      "entries":[
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Rejected",
                          "amountMinor":%d,
                          "sinkingFundId":"%s"
                        }
                      ]
                    }
                    """
                            .formatted(templateId, amountMinor, sinkingFundId)))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private void assertPaycheckApplicationCounts(
      long paycheckCount, long entryCount, long statusCount, long auditCount) {
    assertThat(tableCount("paychecks")).isEqualTo(paycheckCount);
    assertThat(tableCount("paycheck_entries")).isEqualTo(entryCount);
    assertThat(tableCount("entry_status_events")).isEqualTo(statusCount);
    assertThat(tableCount("audit_events")).isEqualTo(auditCount);
  }

  private JsonNode updateTemplate(
      String token, String templateId, String name, long version, int expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                patch("/api/v1/templates/{id}", templateId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name":"%s","description":"Updated","version":%d}
                        """
                            .formatted(name, version)))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode addTemplateEntry(String token, String templateId, String body) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/templates/{id}/entries", templateId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode updateTemplateEntry(
      String token, String entryId, String body, int expectedStatus) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                patch("/api/v1/template-entries/{id}", entryId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode reorderTemplate(
      String token,
      String templateId,
      List<String> entryIds,
      long templateVersion,
      int expectedStatus)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/templates/{id}/entries/reorder", templateId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("entryIds", entryIds, "templateVersion", templateVersion))))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private void deleteTemplateEntry(String token, String entryId, long version, int expectedStatus)
      throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/template-entries/{id}?version={version}", entryId, version)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().is(expectedStatus));
  }

  private JsonNode archiveTemplate(
      String token, String templateId, long version, int expectedStatus) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/templates/{id}/archive", templateId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + version + "}"))
            .andExpect(status().is(expectedStatus))
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private int positionForTemplateEntry(UUID id) {
    return jdbcTemplate.queryForObject(
        "select position from template_entries where id = ?", Integer.class, id);
  }

  private long paycheckCount() {
    return jdbcTemplate.queryForObject("select count(*) from paychecks", Long.class);
  }

  private long tableCount(String tableName) {
    return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
  }

  private long templateCreationAuditCount() {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_events where entity_type = 'TEMPLATE' and action = 'CREATED'",
        Long.class);
  }

  private long auditCount(String entityType, UUID entityId, String action) {
    return jdbcTemplate.queryForObject(
        "select count(*) from audit_events where entity_type = ? and entity_id = ? and action = ?",
        Long.class,
        entityType,
        entityId,
        action);
  }

  private JsonNode entryById(JsonNode template, String entryId) {
    for (JsonNode entry : template.path("entries")) {
      if (entry.path("id").asText().equals(entryId)) {
        return entry;
      }
    }
    throw new AssertionError("Template entry not found: " + entryId);
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
