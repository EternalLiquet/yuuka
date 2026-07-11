package com.yuuka.backend;

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
class PaycheckWorkflowTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

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
