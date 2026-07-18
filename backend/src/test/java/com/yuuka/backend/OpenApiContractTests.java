package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuuka.backend.support.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OpenApiContractTests extends AbstractIntegrationTest {
  private static final Set<String> REQUIRED_PATHS =
      Set.of(
          "/api/v1/auth/login",
          "/api/v1/auth/refresh",
          "/api/v1/auth/logout",
          "/api/v1/me",
          "/api/v1/me/settings",
          "/api/v1/recurring-bills",
          "/api/v1/recurring-bills/timeline",
          "/api/v1/recurring-bills/{definitionId}",
          "/api/v1/expense-ledgers",
          "/api/v1/expense-ledgers/{ledgerId}",
          "/api/v1/expense-ledgers/{ledgerId}/items",
          "/api/v1/expense-ledgers/items/{itemId}",
          "/api/v1/expense-ledgers/{ledgerId}/finalize",
          "/api/v1/expense-ledgers/{ledgerId}/reopen",
          "/api/v1/expense-ledgers/{ledgerId}/settle/bill",
          "/api/v1/expense-ledgers/{ledgerId}/settle/payback",
          "/api/v1/paychecks/{paycheckId}/recurring-bill-imports",
          "/api/v1/paychecks/active",
          "/api/v1/paychecks/history",
          "/api/v1/paychecks",
          "/api/v1/paychecks/from-draft",
          "/api/v1/spending-buckets/performance/rolling",
          "/api/v1/spending-buckets/performance/rolling-90-days",
          "/api/v1/paychecks/from-template",
          "/api/v1/search/entries",
          "/api/v1/paybacks",
          "/api/v1/paybacks/{paybackId}",
          "/api/v1/paybacks/{paybackId}/repayments",
          "/api/v1/sinking-funds",
          "/api/v1/sinking-funds/{fundId}",
          "/api/v1/sinking-funds/{fundId}/archive",
          "/api/v1/sinking-funds/{fundId}/restore",
          "/api/v1/sinking-funds/reorder",
          "/api/v1/sinking-funds/{fundId}/transactions",
          "/api/v1/sinking-funds/{fundId}/withdrawals",
          "/api/v1/sinking-fund-transactions/{transactionId}/reverse",
          "/api/v1/templates",
          "/health/live",
          "/health/version");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @WithMockUser
  void generatedContractContainsRequiredApiAndMatchesCommittedSnapshot() throws Exception {
    String content =
        mockMvc
            .perform(get("/api/openapi"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode generated = objectMapper.readTree(content);
    assertThat(generated.path("openapi").asText()).startsWith("3.");
    assertThat(generated.path("paths").fieldNames()).toIterable().containsAll(REQUIRED_PATHS);
    JsonNode versionOperation = generated.path("paths").path("/health/version").path("get");
    JsonNode versionSchema =
        versionOperation
            .path("responses")
            .path("200")
            .path("content")
            .path("application/json")
            .path("schema");
    assertThat(versionSchema.path("$ref").asText())
        .isEqualTo("#/components/schemas/VersionResponse");
    assertThat(versionOperation.path("security").isArray()).isTrue();
    assertThat(versionOperation.path("security").size()).isZero();
    JsonNode versionResponse = generated.path("components").path("schemas").path("VersionResponse");
    assertThat(versionResponse.path("required").size()).isEqualTo(1);
    assertThat(versionResponse.path("required").get(0).asText()).isEqualTo("version");
    assertThat(versionResponse.path("properties").fieldNames())
        .toIterable()
        .containsExactly("version");
    assertThat(versionResponse.path("properties").path("version").path("type").asText())
        .isEqualTo("string");
    assertThat(versionResponse.path("properties").path("version").path("minLength").asInt())
        .isEqualTo(1);
    JsonNode rollingParameters =
        generated
            .path("paths")
            .path("/api/v1/spending-buckets/performance/rolling")
            .path("get")
            .path("parameters");
    assertThat(rollingParameters.size()).isEqualTo(2);
    assertThat(rollingParameters.findValuesAsText("name")).containsExactly("days", "asOfDate");
    assertThat(rollingParameters.get(0).path("required").asBoolean()).isFalse();
    assertThat(rollingParameters.get(0).path("schema").path("type").asText()).isEqualTo("integer");
    assertThat(rollingParameters.get(0).path("schema").path("format").asText()).isEqualTo("int32");
    assertThat(rollingParameters.get(0).path("schema").path("enum"))
        .allSatisfy((node) -> assertThat(node.isInt()).isTrue())
        .extracting(JsonNode::asInt)
        .containsExactly(30, 90);
    assertThat(rollingParameters.get(0).path("schema").path("default").isInt()).isTrue();
    assertThat(rollingParameters.get(0).path("schema").path("default").asInt()).isEqualTo(30);
    assertThat(rollingParameters.get(1).path("required").asBoolean()).isFalse();

    JsonNode compatibilityParameters =
        generated
            .path("paths")
            .path("/api/v1/spending-buckets/performance/rolling-90-days")
            .path("get")
            .path("parameters");
    assertThat(compatibilityParameters.size()).isEqualTo(1);
    assertThat(compatibilityParameters.get(0).path("name").asText()).isEqualTo("asOfDate");
    assertThat(compatibilityParameters.get(0).path("required").asBoolean()).isFalse();

    JsonNode bucketTransactionParameters =
        generated
            .path("paths")
            .path("/api/v1/entries/{entryId}/bucket-transactions")
            .path("get")
            .path("parameters");
    assertThat(bucketTransactionParameters.findValuesAsText("name")).contains("page", "size");
    JsonNode pageParameter = parameterNamed(bucketTransactionParameters, "page");
    JsonNode pageSchema = pageParameter.path("schema");
    assertThat(pageParameter.path("required").asBoolean()).isFalse();
    assertThat(pageSchema.path("type").asText()).isEqualTo("integer");
    assertThat(pageSchema.path("format").asText()).isEqualTo("int32");
    assertThat(pageSchema.path("minimum").isNumber()).isTrue();
    assertThat(pageSchema.path("minimum").asInt()).isEqualTo(0);
    assertThat(pageSchema.path("default").isInt()).isTrue();
    assertThat(pageSchema.path("default").asInt()).isEqualTo(0);

    JsonNode sizeParameter = parameterNamed(bucketTransactionParameters, "size");
    JsonNode sizeSchema = sizeParameter.path("schema");
    assertThat(sizeParameter.path("required").asBoolean()).isFalse();
    assertThat(sizeSchema.path("type").asText()).isEqualTo("integer");
    assertThat(sizeSchema.path("format").asText()).isEqualTo("int32");
    assertThat(sizeSchema.path("minimum").isNumber()).isTrue();
    assertThat(sizeSchema.path("minimum").asInt()).isEqualTo(1);
    assertThat(sizeSchema.path("maximum").isNumber()).isTrue();
    assertThat(sizeSchema.path("maximum").asInt()).isEqualTo(100);
    assertThat(sizeSchema.path("default").isInt()).isTrue();
    assertThat(sizeSchema.path("default").asInt()).isEqualTo(50);

    JsonNode sinkingFundTransactionParameters =
        generated
            .path("paths")
            .path("/api/v1/sinking-funds/{fundId}/transactions")
            .path("get")
            .path("parameters");
    assertThat(sinkingFundTransactionParameters.findValuesAsText("name")).contains("page", "size");
    JsonNode sinkingFundPageSchema =
        parameterNamed(sinkingFundTransactionParameters, "page").path("schema");
    assertThat(sinkingFundPageSchema.path("type").asText()).isEqualTo("integer");
    assertThat(sinkingFundPageSchema.path("format").asText()).isEqualTo("int32");
    assertThat(sinkingFundPageSchema.path("minimum").asInt()).isEqualTo(0);
    assertThat(sinkingFundPageSchema.path("default").asInt()).isEqualTo(0);
    JsonNode sinkingFundSizeSchema =
        parameterNamed(sinkingFundTransactionParameters, "size").path("schema");
    assertThat(sinkingFundSizeSchema.path("type").asText()).isEqualTo("integer");
    assertThat(sinkingFundSizeSchema.path("format").asText()).isEqualTo("int32");
    assertThat(sinkingFundSizeSchema.path("minimum").asInt()).isEqualTo(1);
    assertThat(sinkingFundSizeSchema.path("maximum").asInt()).isEqualTo(100);
    assertThat(sinkingFundSizeSchema.path("default").asInt()).isEqualTo(50);

    assertBalanceAssignmentSchema(generated, "DraftPaycheckEntryRequest");
    assertBalanceAssignmentSchema(generated, "TemplateApplicationEntryRequest");

    Path generatedPath = Path.of("build", "generated", "openapi.json");
    Files.createDirectories(generatedPath.getParent());
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(generatedPath.toFile(), generated);

    Path committedPath = Path.of("..", "docs", "openapi.json");
    assertThat(committedPath).exists();
    assertThat(objectMapper.readTree(committedPath.toFile())).isEqualTo(generated);
  }

  private JsonNode parameterNamed(JsonNode parameters, String name) {
    for (JsonNode parameter : parameters) {
      if (name.equals(parameter.path("name").asText())) {
        return parameter;
      }
    }
    throw new AssertionError("Missing OpenAPI parameter: " + name);
  }

  private void assertBalanceAssignmentSchema(JsonNode generated, String schemaName) {
    JsonNode properties =
        generated.path("components").path("schemas").path(schemaName).path("properties");
    assertThat(properties.path("paybackId").path("type").asText()).isEqualTo("string");
    assertThat(properties.path("paybackId").path("format").asText()).isEqualTo("uuid");
    assertThat(properties.path("sinkingFundId").path("type").asText()).isEqualTo("string");
    assertThat(properties.path("sinkingFundId").path("format").asText()).isEqualTo("uuid");
  }
}
