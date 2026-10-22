package com.yuuka.backend.mcp.tools;

import static com.yuuka.backend.mcp.tools.McpSchemas.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.common.api.ApplicationVersion;
import com.yuuka.backend.dashboard.api.dto.DashboardAttentionKind;
import com.yuuka.backend.dashboard.api.dto.DashboardSummaryResponse;
import com.yuuka.backend.dashboard.application.DashboardService;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.tools.McpReadSupport.ReadContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMcpEnabled
public class BudgetContextTool implements McpTool {
  public static final String NAME = "yuuka_get_budget_context";

  /** Mirrors the dashboard's cap on needs-attention items. */
  static final int NEEDS_ATTENTION_LIMIT = 5;

  private final McpReadSupport support;
  private final DashboardService dashboard;
  private final ApplicationVersion version;

  public BudgetContextTool(
      McpReadSupport support, DashboardService dashboard, ApplicationVersion version) {
    this.support = support;
    this.dashboard = dashboard;
    this.version = version;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String title() {
    return "Get budget context";
  }

  @Override
  public String description() {
    return "Read-only. Returns the owner's currency, time zone, owner-local date, backend version"
        + " and a compact dashboard summary. The needs-attention list is capped at 5 items and may"
        + " be incomplete. No bank feed exists and writes are disabled.";
  }

  private static ObjectNode constFalse() {
    return JsonNodeFactory.instance.objectNode().put("const", false);
  }

  @Override
  public JsonNode inputSchema() {
    return root(object().describe("No arguments.").build());
  }

  @Override
  public JsonNode outputSchema() {
    ObjectNode attention =
        object()
            .required("kind", enumOf(DashboardAttentionKind.class))
            .required("paycheckId", nullableUuid())
            .required("entryId", nullableUuid())
            .required("expenseLedgerId", nullableUuid())
            .required("name", string())
            .required("amount", integerString())
            .required("dueDate", nullableDate())
            .required("attentionSinceDate", nullableDate())
            .build();
    ObjectNode dashboardSchema =
        object()
            .required("activePaycheckCount", integer())
            .required("totalUnallocated", integerString())
            .required("notPaidEntryCount", integer())
            .required("processingEntryCount", integer())
            .required("needsAttention", array(attention))
            .required("needsAttentionLimit", integer())
            .required("needsAttentionMayBeTruncated", bool())
            .build();
    return envelope(
        object()
            .required("currency", string())
            .required("timeZone", string())
            .required("ownerLocalDate", date())
            .required("backendVersion", string())
            .required("bankFeed", constFalse())
            .required("writesEnabled", constFalse())
            .required("dashboard", dashboardSchema)
            .build());
  }

  @Override
  public McpReadEnvelope<?> call(UUID ownerId, JsonNode arguments) {
    McpArguments.of(arguments, Set.of());
    ReadContext context = support.begin(ownerId);
    DashboardSummaryResponse summary = dashboard.summary(ownerId);
    List<McpViews.AttentionItem> items =
        summary.needsAttention().stream().map(McpViews.AttentionItem::from).toList();
    boolean mayBeTruncated = items.size() >= NEEDS_ATTENTION_LIMIT;
    McpViews.DashboardView view =
        new McpViews.DashboardView(
            summary.active().paycheckCount(),
            McpValues.money(summary.active().totalUnallocatedMinor()),
            summary.active().notPaidEntryCount(),
            summary.active().processingEntryCount(),
            items,
            NEEDS_ATTENTION_LIMIT,
            mayBeTruncated);
    McpViews.BudgetContext data =
        new McpViews.BudgetContext(
            context.currency(),
            context.timeZone(),
            context.asOfDate(),
            version.version(),
            false,
            false,
            view);
    return support.success(
        context,
        NAME,
        "dashboard-summary",
        List.of(
            "The needs-attention list is capped at "
                + NEEDS_ATTENTION_LIMIT
                + " items; additional attention items may exist.",
            "Counts and totals cover currently Active paychecks only.",
            "No bank feed is connected; balances are not reconciled with any bank."),
        new LinkedHashMap<>(),
        null,
        mayBeTruncated,
        data);
  }
}
