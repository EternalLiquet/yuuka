package com.yuuka.backend.mcp.tools;

import static com.yuuka.backend.mcp.tools.McpSchemas.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.tools.McpReadSupport.ReadContext;
import com.yuuka.backend.paycheck.api.dto.PaycheckResponse;
import com.yuuka.backend.paycheck.application.PaycheckService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMcpEnabled
public class GetPaycheckTool implements McpTool {
  public static final String NAME = "yuuka_get_paycheck";

  private final McpReadSupport support;
  private final PaycheckService paychecks;

  public GetPaycheckTool(McpReadSupport support, PaycheckService paychecks) {
    this.support = support;
    this.paychecks = paychecks;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String title() {
    return "Get paycheck";
  }

  @Override
  public String description() {
    return "Read-only. Returns one paycheck with authoritative totals and status counts plus its"
        + " live entries in the owner's custom order, including estimate markers, provenance,"
        + " timestamps and versions. A null amount-like field means unknown. Notes are not"
        + " included.";
  }

  @Override
  public JsonNode inputSchema() {
    return root(object().required("paycheckId", uuid()).build());
  }

  @Override
  public JsonNode outputSchema() {
    return envelope(
        object()
            .required("paycheck", McpPaycheckSchemas.paycheckSummary())
            .required("entries", array(McpPaycheckSchemas.entry()))
            .build());
  }

  @Override
  public McpReadEnvelope<?> call(UUID ownerId, JsonNode arguments) {
    McpArguments args = McpArguments.of(arguments, Set.of("paycheckId"));
    UUID paycheckId = args.uuid("paycheckId");
    ReadContext context = support.begin(ownerId);
    PaycheckResponse paycheck;
    try {
      paycheck = paychecks.get(ownerId, paycheckId, null, null, null, true);
    } catch (ResourceNotFoundException exception) {
      throw McpToolFailure.notFound();
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("paycheckId", paycheckId.toString());
    return support.success(
        context,
        NAME,
        "paycheck-detail",
        List.of(
            "Live (not deleted) entries only, in the owner's custom order.",
            "Spent, remaining and over-budget values are derived by the backend for buckets.",
            "No bank feed is connected; Posted means marked posted in the app."),
        filters,
        null,
        false,
        McpViews.PaycheckDetail.from(paycheck));
  }
}
