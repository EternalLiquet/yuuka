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
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ServiceWorkflowCoverageTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JpaPaycheckRepository paycheckRepository;

  @Test
  void supportsTemplateEditingOrderingLifecycleAndEditedSnapshotApplication() throws Exception {
    String token = register("template-crud@yuuka.local");
    JsonNode template =
        json(
            post("/api/v1/templates")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Biweekly",
                      "description":"Original",
                      "entries":[
                        {
                          "entryType":"BILL",
                          "name":"Electricity",
                          "defaultAmountMinor":13050,
                          "defaultDueOffsetDays":2,
                          "accountName":"Checking",
                          "payee":"Utility Co",
                          "notes":"Autopay"
                        },
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Car repairs",
                          "defaultAmountMinor":5000,
                          "targetMinor":100000,
                          "targetDate":"2026-12-31"
                        }
                      ]
                    }
                    """),
            201);
    String templateId = template.path("id").asText();

    mockMvc
        .perform(get("/api/v1/templates").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));

    template =
        json(
            patch("/api/v1/templates/{id}", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Biweekly plan","description":"Updated","version":%d}
                    """
                        .formatted(template.path("version").asLong())),
            200);

    JsonNode added =
        json(
            post("/api/v1/templates/{id}/entries", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"SPENDING_BUCKET","name":"Groceries","defaultAmountMinor":15000}
                    """),
            201);
    JsonNode updated =
        json(
            patch("/api/v1/template-entries/{id}", added.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"SPENDING_BUCKET",
                      "name":"Food",
                      "defaultAmountMinor":15000,
                      "notes":"Weekly groceries",
                      "version":%d
                    }
                    """
                        .formatted(added.path("version").asLong())),
            200);

    template = getJson(token, "/api/v1/templates/{id}", templateId);
    String firstId = template.path("entries").get(0).path("id").asText();
    String secondId = template.path("entries").get(1).path("id").asText();
    String thirdId = updated.path("id").asText();
    template =
        json(
            post("/api/v1/templates/{id}/entries/reorder", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryIds":["%s","%s","%s"],"templateVersion":%d}
                    """
                        .formatted(thirdId, secondId, firstId, template.path("version").asLong())),
            200);
    assertThat(template.path("entries").get(0).path("name").asText()).isEqualTo("Food");

    JsonNode duplicate =
        json(
            post("/api/v1/templates/{id}/duplicate", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Biweekly alternate\"}"),
            201);
    assertThat(duplicate.path("entries").size()).isEqualTo(3);

    JsonNode archived =
        json(
            post("/api/v1/templates/{id}/archive", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + template.path("version").asLong() + "}"),
            200);
    mockMvc
        .perform(
            get("/api/v1/templates")
                .param("includeArchived", "true")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(2));
    mockMvc
        .perform(
            post("/api/v1/templates/{id}/entries", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"BILL","name":"Blocked","defaultAmountMinor":1}
                    """))
        .andExpect(status().isUnprocessableEntity());

    template =
        json(
            post("/api/v1/templates/{id}/restore", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + archived.path("version").asLong() + "}"),
            200);

    JsonNode snapshot =
        json(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "name":"Edited snapshot",
                      "amountMinor":20000,
                      "incomeDate":"2026-07-17",
                      "source":"Employer",
                      "notes":"Copied once",
                      "entries":[
                        {
                          "entryType":"BILL",
                          "name":"Edited electricity",
                          "amountMinor":13052,
                          "dueDate":"2026-07-20",
                          "accountName":"Checking",
                          "payee":"Utility Co",
                          "notes":"Edited before apply"
                        },
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Car repairs",
                          "amountMinor":5000,
                          "targetMinor":100000,
                          "targetDate":"2026-12-31"
                        }
                      ]
                    }
                    """
                        .formatted(templateId)),
            201);
    assertThat(snapshot.path("unallocatedMinor").asLong()).isEqualTo(1948);
    assertThat(snapshot.path("entries").get(0).path("name").asText())
        .isEqualTo("Edited electricity");

    JsonNode entryToDelete = template.path("entries").get(0);
    mockMvc
        .perform(
            delete("/api/v1/template-entries/{id}", entryToDelete.path("id").asText())
                .param("version", entryToDelete.path("version").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());
  }

  @Test
  void rollsBackInvalidTemplateApplicationBeforeCreatingAPaycheck() throws Exception {
    String token = register("template-rollback@yuuka.local");
    JsonNode template =
        json(
            post("/api/v1/templates")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Too large","entries":[{"entryType":"BILL","name":"Bill","defaultAmountMinor":20000}]}
                    """),
            201);
    long before = paycheckRepository.count();

    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"templateId":"%s","amountMinor":10000,"incomeDate":"2026-07-17"}
                    """
                        .formatted(template.path("id").asText())))
        .andExpect(status().isUnprocessableEntity());

    assertThat(paycheckRepository.count()).isEqualTo(before);
  }

  @Test
  void preservesTemplateBillPaymentMethodWhenOlderEntryUpdateOmitsField() throws Exception {
    String token = register("template-payment-method@yuuka.local");
    JsonNode template =
        json(
            post("/api/v1/templates")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Payment Methods",
                      "entries":[
                        {
                          "entryType":"BILL",
                          "name":"Manual Utility",
                          "defaultAmountMinor":13050,
                          "paymentMethod":"MANUAL",
                          "defaultDueOffsetDays":2,
                          "accountName":"Checking",
                          "payee":"Utility Co",
                          "notes":"Pay by hand"
                        },
                        {"entryType":"SPENDING_BUCKET","name":"Food","defaultAmountMinor":5000}
                      ]
                    }
                    """),
            201);
    String templateId = template.path("id").asText();
    JsonNode manual = template.path("entries").get(0);
    JsonNode bucket = template.path("entries").get(1);

    JsonNode updated =
        json(
            patch("/api/v1/template-entries/{id}", manual.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"BILL",
                      "name":"Manual Utility Updated",
                      "defaultAmountMinor":14050,
                      "defaultDueOffsetDays":3,
                      "accountName":"Checking 2",
                      "payee":"Utility Co",
                      "notes":"Still pay by hand",
                      "version":%d
                    }
                    """
                        .formatted(manual.path("version").asLong())),
            200);
    assertThat(updated.path("paymentMethod").asText()).isEqualTo("MANUAL");

    JsonNode copied =
        json(
            post("/api/v1/templates/{id}/duplicate", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Payment Methods Copy\"}"),
            201);
    assertThat(copied.path("entries").get(0).path("paymentMethod").asText()).isEqualTo("MANUAL");

    JsonNode paycheck =
        json(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"templateId":"%s","amountMinor":25000,"incomeDate":"2026-07-17"}
                    """
                        .formatted(templateId)),
            201);
    assertThat(paycheck.path("entries").get(0).path("paymentMethod").asText()).isEqualTo("MANUAL");

    JsonNode explicit =
        json(
            patch("/api/v1/template-entries/{id}", manual.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"BILL",
                      "name":"Manual Utility Updated",
                      "defaultAmountMinor":14050,
                      "paymentMethod":"AUTOPAY",
                      "defaultDueOffsetDays":3,
                      "accountName":"Checking 2",
                      "payee":"Utility Co",
                      "notes":"Now automatic",
                      "version":%d
                    }
                    """
                        .formatted(updated.path("version").asLong())),
            200);
    assertThat(explicit.path("paymentMethod").asText()).isEqualTo("AUTOPAY");

    mockMvc
        .perform(
            patch("/api/v1/template-entries/{id}", bucket.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"SPENDING_BUCKET",
                      "name":"Food",
                      "defaultAmountMinor":5000,
                      "paymentMethod":"MANUAL",
                      "version":%d
                    }
                    """
                        .formatted(bucket.path("version").asLong())))
        .andExpect(status().isUnprocessableEntity());

    JsonNode override =
        json(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "amountMinor":25000,
                      "incomeDate":"2026-07-31",
                      "entries":[
                        {
                          "entryType":"BILL",
                          "name":"Manual Override",
                          "amountMinor":10000,
                          "paymentMethod":"MANUAL"
                        }
                      ]
                    }
                    """
                        .formatted(templateId)),
            201);
    assertThat(override.path("entries").get(0).path("paymentMethod").asText()).isEqualTo("MANUAL");
  }

  @Test
  void supportsEntryBucketAuditHistoryReorderingAndArchiveWorkflows() throws Exception {
    String token = register("workflow-crud@yuuka.local");
    JsonNode paycheck =
        json(
            post("/api/v1/paychecks")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"July check","source":"Employer Alpha","amountMinor":20000,"incomeDate":"2026-07-17"}
                    """),
            201);
    String paycheckId = paycheck.path("id").asText();
    JsonNode bill =
        addEntry(token, paycheckId, "BILL", "Phone", 5000, ",\"dueDate\":\"2026-07-20\"");
    JsonNode bucket = addEntry(token, paycheckId, "SPENDING_BUCKET", "Food", 15000, "");
    JsonNode temporary = addEntry(token, paycheckId, "BILL", "Temporary", 0, "");

    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/entries", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"BILL","name":"Over allocation","amountMinor":1}
                    """))
        .andExpect(status().isUnprocessableEntity());

    JsonNode detail = getJson(token, "/api/v1/paychecks/{id}", paycheckId);
    detail =
        json(
            post("/api/v1/paychecks/{id}/entries/reorder", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryIds":["%s","%s","%s"],"paycheckVersion":%d}
                    """
                        .formatted(
                            temporary.path("id").asText(),
                            bucket.path("id").asText(),
                            bill.path("id").asText(),
                            detail.path("version").asLong())),
            200);
    assertThat(detail.path("entries").get(0).path("name").asText()).isEqualTo("Temporary");
    temporary = entryByName(detail, "Temporary");
    bucket = entryByName(detail, "Food");
    bill = entryByName(detail, "Phone");

    bill =
        json(
            patch("/api/v1/entries/{id}", bill.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "entryType":"BILL",
                      "name":"Phone",
                      "amountMinor":5000,
                      "dueDate":"2026-07-21",
                      "accountName":"Checking",
                      "payee":"Phone Co",
                      "notes":"Autopay",
                      "version":%d
                    }
                    """
                        .formatted(bill.path("version").asLong())),
            200);

    mockMvc
        .perform(
            delete("/api/v1/entries/{id}", temporary.path("id").asText())
                .param("version", temporary.path("version").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());

    for (String sort : new String[] {"amount", "status", "due-date", "last-edited", "custom"}) {
      mockMvc
          .perform(
              get("/api/v1/paychecks/{id}", paycheckId)
                  .param("sort", sort)
                  .param("ascending", "false")
                  .header("Authorization", bearer(token)))
          .andExpect(status().isOk());
    }
    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", paycheckId)
                .param("status", "NOT_PAID")
                .param("type", "BILL")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(1));

    JsonNode transaction =
        json(
            post("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amountMinor":1235,"description":"Lunch","effectiveDate":"2026-07-18"}
                    """),
            201);
    mockMvc
        .perform(
            post("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amountMinor":0,"effectiveDate":"2026-07-18"}
                    """))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));
    transaction =
        json(
            patch("/api/v1/bucket-transactions/{id}", transaction.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amountMinor":910,"description":"Dinner","effectiveDate":"2026-07-19","version":%d}
                    """
                        .formatted(transaction.path("version").asLong())),
            200);
    mockMvc
        .perform(
            delete("/api/v1/bucket-transactions/{id}", transaction.path("id").asText())
                .param("version", transaction.path("version").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isNoContent());

    bill = changeStatus(token, bill, "POSTED");
    bucket = changeStatus(token, bucket, "POSTED");
    mockMvc
        .perform(
            post("/api/v1/entries/{id}/status", bill.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"toStatus":"POSTED","effectiveAt":"2026-07-20T12:00:00Z","version":%d}
                    """
                        .formatted(bill.path("version").asLong())))
        .andExpect(status().isUnprocessableEntity());

    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}/audit", paycheckId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").isNumber());
    mockMvc
        .perform(
            get("/api/v1/entries/{id}/audit", bill.path("id").asText())
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(3));

    JsonNode closed = getJson(token, "/api/v1/paychecks/{id}", paycheckId);
    assertThat(closed.path("state").asText()).isEqualTo("CLOSED");
    mockMvc
        .perform(
            get("/api/v1/paychecks/history")
                .param("search", "employer")
                .param("from", "2026-07-01")
                .param("to", "2026-07-31")
                .param("oldestFirst", "true")
                .param("page", "0")
                .param("size", "1")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));
    JsonNode reopened =
        json(
            post("/api/v1/paychecks/{id}/reopen", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + closed.path("version").asLong() + "}"),
            200);
    JsonNode archived =
        json(
            delete("/api/v1/paychecks/{id}", paycheckId)
                .param("version", reopened.path("version").asText())
                .header("Authorization", bearer(token)),
            200);
    assertThat(archived.path("state").asText()).isEqualTo("ARCHIVED");
  }

  @Test
  void coversDefensiveBranchesForPaycheckAndBucketServices() throws Exception {
    String token = register("paycheck-branches@yuuka.local");
    JsonNode paycheck =
        json(
            post("/api/v1/paychecks")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"name":"Branches","source":" ","amountMinor":10000,"incomeDate":"2026-07-17","notes":" "}
                    """),
            201);
    String paycheckId = paycheck.path("id").asText();
    JsonNode bill =
        addEntry(
            token,
            paycheckId,
            "BILL",
            "Phone",
            5000,
            ",\"dueDate\":\"2026-07-20\",\"accountName\":\" \",\"payee\":\" \",\"notes\":\" \"");
    JsonNode bucket = addEntry(token, paycheckId, "SPENDING_BUCKET", "Food", 5000, "");
    JsonNode fund =
        addEntry(
            token,
            paycheckId,
            "SINKING_FUND",
            "Repairs",
            0,
            ",\"targetMinor\":100000,\"targetDate\":\"2026-12-31\"");

    mockMvc
        .perform(
            get("/api/v1/paychecks/{id}", paycheckId)
                .param("status", "POSTED")
                .param("type", "BILL")
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(0));

    JsonNode detail = getJson(token, "/api/v1/paychecks/{id}", paycheckId);
    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/entries/reorder", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryIds":["%s","%s","%s"],"paycheckVersion":%d}
                    """
                        .formatted(
                            bill.path("id").asText(),
                            bill.path("id").asText(),
                            bucket.path("id").asText(),
                            detail.path("version").asLong())))
        .andExpect(status().isUnprocessableEntity());

    bill =
        json(
            post("/api/v1/entries/{id}/status", bill.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"toStatus":"PROCESSING","effectiveAt":"2026-07-20T12:00:00Z","note":" "}
                    """),
            200);

    mockMvc
        .perform(
            post("/api/v1/entries/{id}/bucket-transactions", bill.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amountMinor":100,"effectiveDate":"2026-07-18"}
                    """))
        .andExpect(status().isUnprocessableEntity());

    JsonNode transaction =
        json(
            post("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amountMinor":100,"description":" ","effectiveDate":"2026-07-18"}
                    """),
            201);
    mockMvc
        .perform(
            patch("/api/v1/bucket-transactions/{id}", transaction.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amountMinor":200,"effectiveDate":"2026-07-19","version":%d}
                    """
                        .formatted(transaction.path("version").asLong() + 1)))
        .andExpect(status().isConflict());

    detail = getJson(token, "/api/v1/paychecks/{id}", paycheckId);
    bill = entryByName(detail, "Phone");
    bucket = entryByName(detail, "Food");
    fund = entryByName(detail, "Repairs");

    bill = changeStatus(token, bill, "POSTED");
    bucket = changeStatus(token, bucket, "POSTED");
    fund = changeStatus(token, fund, "POSTED");

    JsonNode closed = getJson(token, "/api/v1/paychecks/{id}", paycheckId);
    assertThat(closed.path("state").asText()).isEqualTo("CLOSED");

    mockMvc
        .perform(get("/api/v1/paychecks/history").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));
    mockMvc
        .perform(
            patch("/api/v1/entries/{id}", bill.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"BILL","name":"Phone","amountMinor":5000,"version":%d}
                    """
                        .formatted(bill.path("version").asLong())))
        .andExpect(status().isUnprocessableEntity());
    mockMvc
        .perform(
            post("/api/v1/entries/{id}/bucket-transactions", bucket.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"amountMinor":100,"effectiveDate":"2026-07-20"}
                    """))
        .andExpect(status().isUnprocessableEntity());

    JsonNode reopened =
        json(
            post("/api/v1/paychecks/{id}/reopen", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + closed.path("version").asLong() + "}"),
            200);
    mockMvc
        .perform(
            post("/api/v1/paychecks/{id}/reopen", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + reopened.path("version").asLong() + "}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void coversTemplateDefensiveBranchesAndDefaultApplication() throws Exception {
    String token = register("template-branches@yuuka.local");
    JsonNode template =
        json(
            post("/api/v1/templates")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"Branch Template",
                      "description":" ",
                      "entries":[
                        {
                          "entryType":"BILL",
                          "name":"Bill",
                          "defaultAmountMinor":3000,
                          "defaultDueOffsetDays":2,
                          "accountName":"Checking",
                          "payee":"Utility"
                        },
                        {"entryType":"SPENDING_BUCKET","name":"Food","defaultAmountMinor":2000},
                        {
                          "entryType":"SINKING_FUND",
                          "name":"Repairs",
                          "defaultAmountMinor":1000,
                          "targetMinor":100000,
                          "targetDate":"2026-12-31"
                        }
                      ]
                    }
                    """),
            201);
    String templateId = template.path("id").asText();
    JsonNode firstEntry = template.path("entries").get(0);
    JsonNode secondEntry = template.path("entries").get(1);

    mockMvc
        .perform(get("/api/v1/templates").header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(1));

    JsonNode copied =
        json(
            post("/api/v1/templates/{id}/duplicate", templateId)
                .header("Authorization", bearer(token)),
            201);
    assertThat(copied.path("name").asText()).isEqualTo("Branch Template Copy");
    JsonNode blankNameCopy =
        json(
            post("/api/v1/templates/{id}/duplicate", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \"}"),
            201);
    assertThat(blankNameCopy.path("name").asText()).isEqualTo("Branch Template Copy");

    mockMvc
        .perform(
            patch("/api/v1/template-entries/{id}", firstEntry.path("id").asText())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"BILL","name":"Bill","defaultAmountMinor":3000}
                    """))
        .andExpect(status().isUnprocessableEntity());
    mockMvc
        .perform(
            post("/api/v1/templates/{id}/entries/reorder", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryIds":["%s","%s"],"templateVersion":%d}
                    """
                        .formatted(
                            firstEntry.path("id").asText(),
                            firstEntry.path("id").asText(),
                            template.path("version").asLong())))
        .andExpect(status().isUnprocessableEntity());

    JsonNode paycheck =
        json(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"templateId":"%s","amountMinor":6000,"incomeDate":"2026-07-17"}
                    """
                        .formatted(templateId)),
            201);
    assertThat(paycheck.path("entries").get(0).path("dueDate").asText()).isEqualTo("2026-07-19");
    assertThat(paycheck.path("entries").get(1).path("remainingMinor").asLong()).isEqualTo(2000);

    mockMvc
        .perform(
            post("/api/v1/templates/{id}/archive", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + (template.path("version").asLong() + 1) + "}"))
        .andExpect(status().isConflict());
    JsonNode archived =
        json(
            post("/api/v1/templates/{id}/archive", templateId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + template.path("version").asLong() + "}"),
            200);
    assertThat(archived.path("archived").asBoolean()).isTrue();
    mockMvc
        .perform(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"templateId":"%s","amountMinor":6000,"incomeDate":"2026-07-17"}
                    """
                        .formatted(templateId)))
        .andExpect(status().isUnprocessableEntity());

    assertThat(secondEntry.path("name").asText()).isEqualTo("Food");
  }

  private JsonNode addEntry(
      String token, String paycheckId, String type, String name, long amount, String extra)
      throws Exception {
    return json(
        post("/api/v1/paychecks/{id}/entries", paycheckId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"entryType":"%s","name":"%s","amountMinor":%d%s}
                """
                    .formatted(type, name, amount, extra)),
        201);
  }

  private JsonNode changeStatus(String token, JsonNode entry, String statusName) throws Exception {
    return json(
        post("/api/v1/entries/{id}/status", entry.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"%s","effectiveAt":"2026-07-20T12:00:00Z","version":%d}
                """
                    .formatted(statusName, entry.path("version").asLong())),
        200);
  }

  private JsonNode entryByName(JsonNode paycheck, String name) {
    for (JsonNode entry : paycheck.path("entries")) {
      if (name.equals(entry.path("name").asText())) {
        return entry;
      }
    }
    throw new AssertionError("Missing entry " + name);
  }

  private JsonNode getJson(String token, String path, Object... variables) throws Exception {
    return json(get(path, variables).header("Authorization", bearer(token)), 200);
  }

  private String register(String email) throws Exception {
    JsonNode result =
        json(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email":"%s","password":"Password12345","displayName":"Coverage"}
                    """
                        .formatted(email)),
            201);
    return result.path("accessToken").asText();
  }

  private JsonNode json(MockHttpServletRequestBuilder request, int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
