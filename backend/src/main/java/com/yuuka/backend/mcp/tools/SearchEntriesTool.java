package com.yuuka.backend.mcp.tools;

import static com.yuuka.backend.mcp.tools.McpSchemas.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.tools.McpReadSupport.ReadContext;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.search.api.dto.EntrySearchResultResponse;
import com.yuuka.backend.search.application.EntrySearchService;
import com.yuuka.backend.search.domain.PaycheckContext;
import com.yuuka.backend.search.domain.SearchScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMcpEnabled
public class SearchEntriesTool implements McpTool {
  public static final String NAME = "yuuka_search_entries";
  static final int MAX_QUERY_LENGTH = 200;

  private final McpReadSupport support;
  private final EntrySearchService search;

  public SearchEntriesTool(McpReadSupport support, EntrySearchService search) {
    this.support = support;
    this.search = search;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String title() {
    return "Search entries";
  }

  @Override
  public String description() {
    return "Read-only. Searches live paycheck entries by case-insensitive partial name and/or"
        + " exact amount (decimal string in minor units) across Active, History or both. Results"
        + " are candidates only, not duplicate judgments. At least one criterion is required.";
  }

  @Override
  public JsonNode inputSchema() {
    return root(
        object()
            .optional(
                "query",
                string("Case-insensitive partial entry name.")
                    .put("minLength", 1)
                    .put("maxLength", MAX_QUERY_LENGTH))
            .optional(
                "amountMinor",
                integerString().put("description", "Exact amount in minor units, as a string."))
            .optional("scope", enumOf(SearchScope.class).put("default", "ALL"))
            .optional("page", integer().put("minimum", 0).put("default", 0))
            .optional(
                "size", integer(1, ListPaychecksTool.MAX_SIZE, ListPaychecksTool.DEFAULT_SIZE))
            .build());
  }

  @Override
  public JsonNode outputSchema() {
    var candidate =
        object()
            .required("matchKind", enumString("CANDIDATE"))
            .required("entryId", uuid())
            .required("paycheckId", uuid())
            .required("entryName", string())
            .required("amount", integerString())
            .required("amountEstimated", bool())
            .required("entryType", enumOf(EntryType.class))
            .required("paymentMethod", nullable(enumOf(EntryPaymentMethod.class)))
            .required("status", enumOf(EntryStatus.class))
            .required("paycheckName", string())
            .required("paycheckIncomeDate", date())
            .required("paycheckContext", enumOf(PaycheckContext.class))
            .build();
    return envelope(object().required("items", array(candidate)).build());
  }

  @Override
  public McpReadEnvelope<?> call(UUID ownerId, JsonNode arguments) {
    McpArguments args =
        McpArguments.of(arguments, Set.of("query", "amountMinor", "scope", "page", "size"));
    String rawQuery = args.string("query", false, 0, MAX_QUERY_LENGTH + 100);
    String query = rawQuery == null ? null : rawQuery.trim();
    if (query != null && query.isEmpty()) {
      query = null;
    }
    if (query != null && query.length() > MAX_QUERY_LENGTH) {
      throw McpToolFailure.invalid("$.query: length must be at most " + MAX_QUERY_LENGTH + ".");
    }
    Long amount = args.integerString("amountMinor");
    SearchScope scope = args.enumValue("scope", SearchScope.class, SearchScope.ALL);
    int page = args.integer("page", 0, 0, Integer.MAX_VALUE);
    int size = args.integer("size", ListPaychecksTool.DEFAULT_SIZE, 1, ListPaychecksTool.MAX_SIZE);
    if (query == null && amount == null) {
      throw McpToolFailure.invalid("$.query: provide a non-empty query or amountMinor.");
    }
    ReadContext context = support.begin(ownerId);
    PageResponse<EntrySearchResultResponse> result;
    try {
      result = search.search(ownerId, query, amount, scope, page, size);
    } catch (BusinessRuleException exception) {
      throw McpToolFailure.invalid("$.query: " + exception.getMessage());
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    if (query != null) {
      filters.put("query", query);
    }
    if (amount != null) {
      filters.put("amountMinor", McpValues.money(amount));
    }
    filters.put("scope", scope.name());
    return support.success(
        context,
        NAME,
        "entry-search-" + scope.name().toLowerCase(java.util.Locale.ROOT),
        List.of(
            "Results are candidates matching the criteria; they are not duplicate judgments.",
            "Soft-deleted entries are excluded.",
            "Paginated: later pages are read separately and may reflect later changes."),
        filters,
        McpReadSupport.page(result),
        false,
        new McpViews.SearchResults(
            result.items().stream().map(McpViews.SearchCandidate::from).toList()));
  }
}
