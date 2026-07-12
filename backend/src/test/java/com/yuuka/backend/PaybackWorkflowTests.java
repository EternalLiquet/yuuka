package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class PaybackWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

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
        .andExpect(jsonPath("$.items[1].state").value("PAID_OFF"));
  }

  @Test
  void appliesAndReversesPostedEntryRepaymentsExactlyOnce() throws Exception {
    String token = registerAndGetAccessToken("paybacks-lifecycle@yuuka.local");
    JsonNode payback = createPayback(token, "Car repair", 10000, 10000);
    JsonNode paycheck = createPaycheck(token, 10000);
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
    MvcResult result =
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
                          "paybackId": "%s"
                        }
                        """
                            .formatted(name, amountMinor, paybackId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paybackId").value(paybackId))
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
