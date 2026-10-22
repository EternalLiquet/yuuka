package com.yuuka.backend.mcp.tools;

import static com.yuuka.backend.mcp.tools.McpSchemas.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.tools.McpReadSupport.ReadContext;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.recurring.api.dto.RecurringBillTimelineResponse;
import com.yuuka.backend.recurring.application.RecurringBillService;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMcpEnabled
public class RecurringCoverageTool implements McpTool {
  public static final String NAME = "yuuka_get_recurring_coverage";

  private final McpReadSupport support;
  private final RecurringBillService recurring;

  public RecurringCoverageTool(McpReadSupport support, RecurringBillService recurring) {
    this.support = support;
    this.recurring = recurring;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String title() {
    return "Get recurring bill coverage";
  }

  @Override
  public String description() {
    return "Read-only. Lists occurrences of currently active recurring Bill definitions in an"
        + " inclusive date range (at most 366 days) with the paycheck entries linked to each."
        + " UNLINKED means no paycheck entry is linked, not that the bill is unpaid.";
  }

  @Override
  public JsonNode inputSchema() {
    return root(
        object()
            .required("from", date().put("description", "Inclusive start date."))
            .required("through", date().put("description", "Inclusive end date; max 366 days."))
            .build());
  }

  @Override
  public JsonNode outputSchema() {
    var linked =
        object()
            .required("entryId", uuid())
            .required("paycheckId", uuid())
            .required("paycheckName", string())
            .required("status", enumOf(EntryStatus.class))
            .build();
    var occurrence =
        object()
            .required("definitionId", uuid())
            .required("definitionVersion", integerString())
            .required("occurrenceDate", date())
            .required("name", string())
            .required("amountMode", enumOf(RecurringBillAmountMode.class))
            .required("typicalAmount", nullableIntegerString())
            .required("planningAmount", nullableIntegerString())
            .required("amount", nullableIntegerString())
            .required("amountEntered", bool())
            .required("occurrenceAmountVersion", nullableIntegerString())
            .required("paymentMethod", nullable(enumOf(EntryPaymentMethod.class)))
            .required("accountName", nullableString())
            .required("payee", nullableString())
            .required("linkStatus", enumString("LINKED", "UNLINKED"))
            .required("linkedEntryCount", integer())
            .required("linkedEntries", array(linked))
            .build();
    return envelope(
        object()
            .required("from", date())
            .required("through", date())
            .required("items", array(occurrence))
            .build());
  }

  @Override
  public McpReadEnvelope<?> call(UUID ownerId, JsonNode arguments) {
    McpArguments args = McpArguments.of(arguments, Set.of("from", "through"));
    LocalDate from = args.date("from", true);
    LocalDate through = args.date("through", true);
    if (ChronoUnit.DAYS.between(from, through) >= 366) {
      throw McpToolFailure.invalid("$.through: inclusive range must not exceed 366 days.");
    }
    ReadContext context = support.begin(ownerId);
    RecurringBillTimelineResponse timeline;
    try {
      timeline = recurring.timeline(ownerId, from, through);
    } catch (BusinessRuleException exception) {
      throw McpToolFailure.invalid("$.through: " + exception.getMessage());
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("from", from.toString());
    filters.put("through", through.toString());
    return support.success(
        context,
        NAME,
        "recurring-occurrences",
        List.of(
            "Only currently active recurring Bill definitions are included.",
            "UNLINKED means no paycheck entry is linked to the occurrence; it does not mean unpaid.",
            "A null amount is unknown or not yet entered, not zero.",
            "No bank feed is connected; coverage reflects links inside the app only."),
        filters,
        null,
        false,
        new McpViews.RecurringCoverage(
            timeline.from(),
            timeline.through(),
            timeline.items().stream().map(McpViews.RecurringOccurrence::from).toList()));
  }
}
