package com.yuuka.backend.mcp.tools;

import static com.yuuka.backend.mcp.tools.McpSchemas.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuuka.backend.common.api.PageResponse;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.tools.McpReadSupport.ReadContext;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.application.PaycheckService;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMcpEnabled
public class ListPaychecksTool implements McpTool {
  public static final String NAME = "yuuka_list_paychecks";
  static final int DEFAULT_SIZE = 25;
  static final int MAX_SIZE = 100;

  enum View {
    ACTIVE,
    HISTORY
  }

  private final McpReadSupport support;
  private final PaycheckService paychecks;

  public ListPaychecksTool(McpReadSupport support, PaycheckService paychecks) {
    this.support = support;
    this.paychecks = paychecks;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String title() {
    return "List paychecks";
  }

  @Override
  public String description() {
    return "Read-only. Lists compact paycheck summaries (no entries) for the Active or History"
        + " view in the app's own order, with page metadata. from/through filter History income"
        + " dates only and are rejected for Active. Default page size 25, maximum 100.";
  }

  @Override
  public JsonNode inputSchema() {
    return root(
        object()
            .required("view", enumOf(View.class))
            .optional("page", integer().put("minimum", 0).put("default", 0))
            .optional("size", integer(1, MAX_SIZE, DEFAULT_SIZE))
            .optional("from", date().put("description", "History only. Inclusive income date."))
            .optional("through", date().put("description", "History only. Inclusive income date."))
            .build());
  }

  @Override
  public JsonNode outputSchema() {
    return envelope(
        object().required("items", array(McpPaycheckSchemas.paycheckSummary())).build());
  }

  @Override
  public McpReadEnvelope<?> call(UUID ownerId, JsonNode arguments) {
    McpArguments args =
        McpArguments.of(arguments, Set.of("view", "page", "size", "from", "through"));
    View view = args.enumValue("view", View.class, null);
    int page = args.integer("page", 0, 0, Integer.MAX_VALUE);
    int size = args.integer("size", DEFAULT_SIZE, 1, MAX_SIZE);
    LocalDate from = args.date("from", false);
    LocalDate through = args.date("through", false);
    if (view == View.ACTIVE && (from != null || through != null)) {
      throw McpToolFailure.invalid("$.from: date filters apply only to the HISTORY view.");
    }
    if (from != null && through != null && from.isAfter(through)) {
      throw McpToolFailure.invalid("$.from: must not be after through.");
    }
    ReadContext context = support.begin(ownerId);
    PageResponse<PaycheckResponse> result =
        view == View.ACTIVE
            ? paychecks.active(ownerId, page, size)
            : paychecks.history(ownerId, null, from, through, false, page, size);
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("view", view.name());
    if (from != null) {
      filters.put("from", from.toString());
    }
    if (through != null) {
      filters.put("through", through.toString());
    }
    McpViews.PaycheckList data =
        new McpViews.PaycheckList(
            result.items().stream().map(McpViews.PaycheckSummary::from).toList());
    return support.success(
        context,
        NAME,
        view == View.ACTIVE ? "active-paychecks" : "history-paychecks",
        List.of(
            "Paginated: later pages are read separately and may reflect later changes.",
            "Dates filter paycheck income dates in the History view only.",
            "No bank feed is connected; results are the app's own records."),
        filters,
        McpReadSupport.page(result),
        false,
        data);
  }
}
