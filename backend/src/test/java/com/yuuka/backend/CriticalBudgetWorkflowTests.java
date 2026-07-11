package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class CriticalBudgetWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void appendsEveryForwardAndBackwardStatusTransitionAndSupportsCloseReopen() throws Exception {
    String token = register("status@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Status Paycheck", 15000);
    String paycheckId = paycheck.path("id").asText();
    JsonNode entry =
        json(
            post("/api/v1/paychecks/{id}/entries", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"BILL","name":"Verizon","amountMinor":15000}
                    """),
            201);
    String entryId = entry.path("id").asText();

    JsonNode processing = changeStatus(token, entryId, "PROCESSING", "2026-07-08T12:00:00Z", 0);
    JsonNode posted =
        changeStatus(
            token, entryId, "POSTED", "2026-07-09T12:00:00Z", processing.path("version").asLong());
    JsonNode backToProcessing =
        changeStatus(
            token, entryId, "PROCESSING", "2026-07-10T12:00:00Z", posted.path("version").asLong());

    mockMvc
        .perform(
            get("/api/v1/entries/{id}/status-history", entryId)
                .header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalItems").value(4))
        .andExpect(jsonPath("$.items[0].toStatus").value("PROCESSING"))
        .andExpect(jsonPath("$.items[0].effectiveAt").value("2026-07-10T12:00:00Z"))
        .andExpect(jsonPath("$.items[0].recordedAt").isString());

    JsonNode detail = getPaycheck(token, paycheckId);
    close(token, paycheckId, detail.path("version").asLong(), 422);

    changeStatus(
        token,
        entryId,
        "POSTED",
        "2026-07-10T13:00:00Z",
        backToProcessing.path("version").asLong());
    detail = getPaycheck(token, paycheckId);
    JsonNode closed = close(token, paycheckId, detail.path("version").asLong(), 200);
    assertThat(closed.path("state").asText()).isEqualTo("CLOSED");

    JsonNode reopened =
        json(
            post("/api/v1/paychecks/{id}/reopen", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + closed.path("version").asLong() + "}"),
            200);
    assertThat(reopened.path("state").asText()).isEqualTo("ACTIVE");
  }

  @Test
  void appliesTemplateAsIndependentExactCentSnapshot() throws Exception {
    String token = register("template@yuuka.local");
    JsonNode template =
        json(
            post("/api/v1/templates")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"UTILITIES 1/2",
                      "entries":[
                        {"entryType":"BILL","name":"Electricity","defaultAmountMinor":13050},
                        {"entryType":"BILL","name":"Other","defaultAmountMinor":180870}
                      ]
                    }
                    """),
            201);
    String templateId = template.path("id").asText();

    JsonNode paycheck =
        json(
            post("/api/v1/paychecks/from-template")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "templateId":"%s",
                      "amountMinor":193923,
                      "incomeDate":"2026-07-10"
                    }
                    """
                        .formatted(templateId)),
            201);
    assertThat(paycheck.path("allocatedMinor").asLong()).isEqualTo(193920);
    assertThat(paycheck.path("unallocatedMinor").asLong()).isEqualTo(3);

    JsonNode copiedElectricity = paycheck.path("entries").get(0);
    json(
        patch("/api/v1/entries/{id}", copiedElectricity.path("id").asText())
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "entryType":"BILL",
                  "name":"Electricity",
                  "amountMinor":13052,
                  "version":%d
                }
                """
                    .formatted(copiedElectricity.path("version").asLong())),
        200);

    mockMvc
        .perform(get("/api/v1/templates/{id}", templateId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].defaultAmountMinor").value(13050));
  }

  @Test
  void calculatesBucketTransactionsAndNegativeCorrections() throws Exception {
    String token = register("bucket@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Buckets", 5000);
    String paycheckId = paycheck.path("id").asText();
    JsonNode bucket =
        json(
            post("/api/v1/paychecks/{id}/entries", paycheckId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"entryType":"SPENDING_BUCKET","name":"Work Food","amountMinor":5000}
                    """),
            201);
    String entryId = bucket.path("id").asText();
    addBucketTransaction(token, entryId, 1235);
    addBucketTransaction(token, entryId, 910);
    addBucketTransaction(token, entryId, -500);

    mockMvc
        .perform(get("/api/v1/paychecks/{id}", paycheckId).header("Authorization", bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].spentMinor").value(1645))
        .andExpect(jsonPath("$.entries[0].remainingMinor").value(3355))
        .andExpect(jsonPath("$.entries[0].overBudget").value(false));
  }

  @Test
  void returnsConflictForAStalePaycheckVersion() throws Exception {
    String token = register("stale@yuuka.local");
    JsonNode paycheck = createPaycheck(token, "Original", 10000);
    String id = paycheck.path("id").asText();
    long version = paycheck.path("version").asLong();
    String update =
        """
        {"name":"Updated","amountMinor":10000,"incomeDate":"2026-07-17","version":%d}
        """
            .formatted(version);

    json(
        patch("/api/v1/paychecks/{id}", id)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(update),
        200);
    mockMvc
        .perform(
            patch("/api/v1/paychecks/{id}", id)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  private JsonNode createPaycheck(String token, String name, long amountMinor) throws Exception {
    return json(
        post("/api/v1/paychecks")
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"name":"%s","amountMinor":%d,"incomeDate":"2026-07-17"}
                """
                    .formatted(name, amountMinor)),
        201);
  }

  private JsonNode getPaycheck(String token, String id) throws Exception {
    return json(get("/api/v1/paychecks/{id}", id).header("Authorization", bearer(token)), 200);
  }

  private JsonNode changeStatus(
      String token, String entryId, String statusName, String effectiveAt, long version)
      throws Exception {
    return json(
        post("/api/v1/entries/{id}/status", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"toStatus":"%s","effectiveAt":"%s","note":"test","version":%d}
                """
                    .formatted(statusName, effectiveAt, version)),
        200);
  }

  private JsonNode close(String token, String paycheckId, long version, int expectedStatus)
      throws Exception {
    return json(
        post("/api/v1/paychecks/{id}/close", paycheckId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"version\":" + version + "}"),
        expectedStatus);
  }

  private void addBucketTransaction(String token, String entryId, long amount) throws Exception {
    json(
        post("/api/v1/entries/{id}/bucket-transactions", entryId)
            .header("Authorization", bearer(token))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"amountMinor":%d,"effectiveDate":"2026-07-10"}
                """
                    .formatted(amount)),
        201);
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

  private JsonNode json(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      int expectedStatus)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
  }

  private String bearer(String token) {
    return "Bearer " + token;
  }
}
