package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AppendOnlyPersistenceTests extends AbstractIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void databaseRejectsUpdatesAndDeletesForStatusAndAuditHistory() throws Exception {
    String token = register();
    JsonNode paycheck =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/v1/paychecks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Immutable history","amountMinor":100,"incomeDate":"2026-07-17"}
                            """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    JsonNode entry =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/v1/paychecks/{id}/entries", paycheck.path("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"entryType":"BILL","name":"Bill","amountMinor":100}
                            """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

    UUID statusId =
        jdbcTemplate.queryForObject(
            "select id from entry_status_events where entry_id = ? order by recorded_at desc limit 1",
            UUID.class,
            UUID.fromString(entry.path("id").asText()));
    UUID auditId =
        jdbcTemplate.queryForObject(
            "select id from audit_events where entity_id = ? order by recorded_at desc limit 1",
            UUID.class,
            UUID.fromString(paycheck.path("id").asText()));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update entry_status_events set note = 'changed' where id = ?", statusId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbcTemplate.update("delete from entry_status_events where id = ?", statusId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "update audit_events set action = 'changed' where id = ?", auditId))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbcTemplate.update("delete from audit_events where id = ?", auditId))
        .isInstanceOf(DataAccessException.class);
  }

  private String register() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email":"append-only@yuuka.local","password":"Password12345","displayName":"Test"}
                        """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("accessToken").asText();
  }
}
