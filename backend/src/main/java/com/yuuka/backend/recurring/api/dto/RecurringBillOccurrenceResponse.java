package com.yuuka.backend.recurring.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecurringBillOccurrenceResponse(
    UUID definitionId,
    long definitionVersion,
    LocalDate occurrenceDate,
    String name,
    RecurringBillAmountMode amountMode,
    @Schema(types = {"integer", "null"}) Long typicalAmountMinor,
    @Schema(types = {"integer", "null"}) Long amountMinor,
    boolean amountEntered,
    @Schema(types = {"integer", "null"}) Long occurrenceAmountVersion,
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
