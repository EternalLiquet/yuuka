package com.yuuka.backend.recurring.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecurringBillOccurrenceResponse(
    UUID definitionId,
    long definitionVersion,
    LocalDate occurrenceDate,
    String name,
    long typicalAmountMinor,
    EntryPaymentMethod paymentMethod,
    String accountName,
    String payee,
    String notes,
    int importCount,
    List<RecurringBillImportSummaryResponse> imports) {
  public RecurringBillOccurrenceResponse {
    imports = List.copyOf(imports);
  }
}
