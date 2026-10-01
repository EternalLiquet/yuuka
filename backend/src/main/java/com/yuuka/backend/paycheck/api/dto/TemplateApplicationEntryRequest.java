package com.yuuka.backend.paycheck.api.dto;

import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record TemplateApplicationEntryRequest(
    @NotNull EntryType entryType,
    @NotBlank @Size(max = 160) String name,
    @PositiveOrZero(message = "Amount must be greater than or equal to $0.00.") long amountMinor,
    EntryPaymentMethod paymentMethod,
    LocalDate dueDate,
    @Size(max = 160) String accountName,
    @Size(max = 160) String payee,
    @Size(max = 2000) String notes,
    @PositiveOrZero(message = "Target amount must be greater than or equal to $0.00.")
        Long targetMinor,
    LocalDate targetDate,
    UUID paybackId,
    UUID sinkingFundId,
    UUID sourceRecurringBillDefinitionId,
    LocalDate sourceRecurringOccurrenceDate,
    @PositiveOrZero Long recurringDefinitionVersion,
    @PositiveOrZero Long occurrenceAmountVersion,
    boolean amountEstimated,
    boolean confirmDuplicateOccurrence) {
  public DraftPaycheckEntryRequest toDraft() {
    return new DraftPaycheckEntryRequest(
        entryType,
        name,
        amountMinor,
        paymentMethod,
        dueDate,
        accountName,
        payee,
        notes,
        targetMinor,
        targetDate,
        paybackId,
        sinkingFundId,
        sourceRecurringBillDefinitionId,
        sourceRecurringOccurrenceDate,
        recurringDefinitionVersion,
        occurrenceAmountVersion,
        amountEstimated,
        confirmDuplicateOccurrence);
  }
}
