package com.yuuka.backend.mcp.tools;

import static com.yuuka.backend.mcp.tools.McpSchemas.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckState;

/** Output schemas shared by tools that return paycheck facts. */
final class McpPaycheckSchemas {
  private McpPaycheckSchemas() {}

  static ObjectNode paycheckSummary() {
    return object()
        .required("id", uuid())
        .required("name", string())
        .required("source", nullableString())
        .required("amount", integerString())
        .required("incomeDate", date())
        .required("state", enumOf(PaycheckState.class))
        .required("allocated", integerString())
        .required("unallocated", integerString())
        .required("posted", integerString())
        .required("processing", integerString())
        .required("notPaid", integerString())
        .required("allocationPercent", number())
        .required("completionPercent", number())
        .required("notPaidCount", integer())
        .required("processingCount", integer())
        .required("postedCount", integer())
        .required("entryCount", integer())
        .required("requiresAttention", bool())
        .required("templateSourceId", nullableUuid())
        .required("createdAt", instant())
        .required("updatedAt", instant())
        .required("closedAt", nullableInstant())
        .required("reopenedAt", nullableInstant())
        .required("archivedAt", nullableInstant())
        .required("version", integerString())
        .build();
  }

  static ObjectNode entry() {
    return object()
        .required("id", uuid())
        .required("entryType", enumOf(EntryType.class))
        .required("paymentMethod", nullable(enumOf(EntryPaymentMethod.class)))
        .required("name", string())
        .required("amount", integerString())
        .required("amountEstimated", bool())
        .required("status", enumOf(EntryStatus.class))
        .required("position", integer())
        .required("dueDate", nullableDate())
        .required("accountName", nullableString())
        .required("payee", nullableString())
        .required("target", nullableIntegerString())
        .required("targetDate", nullableDate())
        .required("paybackId", nullableUuid())
        .required("sinkingFundId", nullableUuid())
        .required("sourceRecurringBillDefinitionId", nullableUuid())
        .required("sourceRecurringOccurrenceDate", nullableDate())
        .required("sourceExpenseLedgerId", nullableUuid())
        .required("spent", nullableIntegerString())
        .required("remaining", nullableIntegerString())
        .required("overBudget", nullableBool())
        .required("createdAt", instant())
        .required("updatedAt", instant())
        .required("version", integerString())
        .build();
  }
}
