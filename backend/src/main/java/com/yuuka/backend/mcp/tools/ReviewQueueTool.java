package com.yuuka.backend.mcp.tools;

import static com.yuuka.backend.mcp.tools.McpSchemas.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.mcp.config.ConditionalOnMcpEnabled;
import com.yuuka.backend.mcp.tools.McpReadSupport.ReadContext;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import com.yuuka.backend.review.application.BudgetReviewService;
import com.yuuka.backend.review.domain.ReviewAmountBasis;
import com.yuuka.backend.review.domain.ReviewCheck;
import com.yuuka.backend.review.domain.ReviewDefinitionState;
import com.yuuka.backend.review.domain.ReviewMatchReason;
import com.yuuka.backend.review.domain.ReviewQueue;
import com.yuuka.backend.review.domain.ReviewScope;
import com.yuuka.backend.review.domain.ReviewSeverity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMcpEnabled
public class ReviewQueueTool implements McpTool {
  public static final String NAME = "yuuka_get_review_queue";

  private final McpReadSupport support;
  private final BudgetReviewService review;

  public ReviewQueueTool(McpReadSupport support, BudgetReviewService review) {
    this.support = support;
    this.review = review;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String title() {
    return "Get bookkeeping review queue";
  }

  @Override
  public String description() {
    return "Read-only. Lists bookkeeping questions derived from existing records: recurring"
        + " occurrences in an inclusive date window (at most 93 days) that are unlinked, multiply"
        + " linked or have unknown amounts, plus manual Bills, aged Processing entries, unallocated"
        + " paychecks and over-budget buckets across ALL Active paychecks. Findings are questions"
        + " with evidence, not conclusions: unlinked is not unpaid, multiple links may be"
        + " intentional, possible matches are unconfirmed. There is no bank feed. Nothing is"
        + " changed.";
  }

  @Override
  public JsonNode inputSchema() {
    return root(
        object()
            .required(
                "from", date().put("description", "Inclusive start of the occurrence window."))
            .required(
                "through",
                date().put("description", "Inclusive end of the occurrence window; max 93 days."))
            .optional("page", integer().put("minimum", 0).put("default", 0))
            .optional(
                "size", integer(1, BudgetReviewService.MAX_SIZE, BudgetReviewService.DEFAULT_SIZE))
            .build());
  }

  @Override
  public JsonNode outputSchema() {
    var provenance =
        object()
            .required("definitionId", uuid())
            .required("definitionState", enumOf(ReviewDefinitionState.class))
            .required("currentDefinitionVersion", integerString())
            .required("currentDefinitionName", string())
            .required("definitionUpdatedAfterEntryCreated", bool())
            .required("sourceOccurrenceDate", date())
            .build();
    var entry =
        object()
            .required("entryId", uuid())
            .required("paycheckId", uuid())
            .required("paycheckName", string())
            .required("paycheckIncomeDate", date())
            .required("paycheckState", enumOf(PaycheckState.class))
            .required("name", string())
            .required("entryType", enumOf(EntryType.class))
            .required("status", enumOf(EntryStatus.class))
            .required("amount", integerString())
            .required("version", integerString())
            .required("dueDate", nullableDate())
            .required("paymentMethod", nullable(enumOf(EntryPaymentMethod.class)))
            .required("amountEstimated", bool())
            .required("amountBasis", nullable(enumOf(ReviewAmountBasis.class)))
            .required("provenance", nullable(provenance))
            .build();
    var definition =
        object()
            .required("id", uuid())
            .required("version", integerString())
            .required("name", string())
            .required("amountMode", enumOf(RecurringBillAmountMode.class))
            .required("typicalAmount", nullableIntegerString())
            .required("planningAmount", nullableIntegerString())
            .required("state", enumOf(ReviewDefinitionState.class))
            .build();
    var occurrence =
        object()
            .required("date", date())
            .required("amount", nullableIntegerString())
            .required("amountEntered", bool())
            .required("amountBasis", enumOf(ReviewAmountBasis.class))
            .required("occurrenceAmountVersion", nullableIntegerString())
            .build();
    var paycheck =
        object()
            .required("id", uuid())
            .required("name", string())
            .required("version", integerString())
            .required("incomeDate", date())
            .required("state", enumOf(PaycheckState.class))
            .required("amount", integerString())
            .required("allocated", integerString())
            .required("unallocated", integerString())
            .build();
    var possibleMatch =
        object()
            .required("entry", entry)
            .required("reasons", array(enumOf(ReviewMatchReason.class)))
            .build();
    var search =
        object()
            .required("criteria", string())
            .required("limit", integer())
            .required("returned", integer())
            .required("complete", bool())
            .build();
    var processing =
        object()
            .required("effectiveAt", instant())
            .required("recordedAt", instant())
            .required("processingSinceDate", date())
            .required("agedOnOrBeforeDate", date())
            .required("daysProcessing", integer())
            .build();
    var bucket =
        object()
            .required("budget", integerString())
            .required("spent", integerString())
            .required("remaining", integerString())
            .required("overage", integerString())
            .build();
    var evidence =
        object()
            .required("definition", nullable(definition))
            .required("occurrence", nullable(occurrence))
            .required("paycheck", nullable(paycheck))
            .required("entry", nullable(entry))
            .required("linkedEntries", array(entry))
            .required("possibleMatches", array(possibleMatch))
            .required("candidateSearch", nullable(search))
            .required("processing", nullable(processing))
            .required("bucket", nullable(bucket))
            .build();
    var finding =
        object()
            .required("checkCode", enumOf(ReviewCheck.class))
            .required("severity", enumOf(ReviewSeverity.class))
            .required("scope", enumOf(ReviewScope.class))
            .required("question", string())
            .required("primaryDate", nullableDate())
            .required("paycheckId", nullableUuid())
            .required("entryId", nullableUuid())
            .required("definitionId", nullableUuid())
            .required("evidence", evidence)
            .build();
    var summary = object();
    for (ReviewCheck check : ReviewCheck.values()) {
      summary.required(check.name(), integer());
    }
    return envelope(
        object()
            .required("from", date())
            .required("through", date())
            .required("paycheckScope", enumString(ReviewScope.ALL_ACTIVE_PAYCHECKS.name()))
            .required("summary", summary.build())
            .required("findings", array(finding))
            .build());
  }

  @Override
  public McpReadEnvelope<?> call(UUID ownerId, JsonNode arguments) {
    McpArguments args = McpArguments.of(arguments, Set.of("from", "through", "page", "size"));
    LocalDate from = args.date("from", true);
    LocalDate through = args.date("through", true);
    int page = args.integer("page", 0, 0, Integer.MAX_VALUE);
    int size =
        args.integer("size", BudgetReviewService.DEFAULT_SIZE, 1, BudgetReviewService.MAX_SIZE);
    try {
      BudgetReviewService.validateWindow(from, through);
    } catch (BusinessRuleException exception) {
      throw McpToolFailure.invalid("$.through: " + exception.getMessage());
    }
    ReviewQueue queue;
    try {
      queue = review.review(ownerId, from, through, page, size);
    } catch (ResourceNotFoundException exception) {
      throw McpToolFailure.notFound();
    } catch (BusinessRuleException exception) {
      if ("MONEY_AMOUNT_OVERFLOW".equals(exception.code())) {
        throw new McpToolFailure(exception.code(), exception.getMessage(), false);
      }
      throw McpToolFailure.invalid(exception.getMessage());
    }
    List<String> limitations = new ArrayList<>(queue.limitations());
    boolean incomplete =
        queue.findings().stream()
            .anyMatch(
                f ->
                    f.evidence().candidateSearch() != null
                        && !f.evidence().candidateSearch().complete());
    if (incomplete) {
      limitations.add(
          "At least one possible-match list is incomplete: its candidate search reached the limit,"
              + " see candidateSearch.complete on each finding.");
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("from", from.toString());
    filters.put("through", through.toString());
    ReadContext context =
        new ReadContext(queue.startedAt(), queue.asOfDate(), queue.timeZone(), queue.currency());
    return support.success(
        context,
        NAME,
        "budget-review-queue",
        limitations,
        filters,
        new McpReadEnvelope.Page(
            queue.page(),
            queue.size(),
            McpValues.count(queue.totalItems()),
            queue.totalPages(),
            queue.hasNext()),
        false,
        McpReadEnvelope.SNAPSHOT,
        McpReviewViews.Queue.from(queue));
  }
}
