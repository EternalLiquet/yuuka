package com.yuuka.backend.paycheck.application;

import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.paycheck.api.dto.CreateEntryRequest;
import com.yuuka.backend.paycheck.api.dto.DraftPaycheckEntryRequest;
import com.yuuka.backend.paycheck.api.dto.UpdateEntryRequest;
import com.yuuka.backend.paycheck.domain.EntryPaymentMethod;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PaycheckEntryMutationHelper {
  PaycheckEntry draftEntry(
      UUID ownerId, UUID paycheckId, DraftPaycheckEntryRequest source, int position) {
    PaycheckEntry entry =
        new PaycheckEntry(
            ownerId,
            paycheckId,
            source.entryType(),
            source.name().trim(),
            source.amountMinor(),
            position,
            paymentMethodForCreate(source.entryType(), source.paymentMethod()),
            billValue(source.entryType(), source.dueDate()),
            billValue(source.entryType(), normalizeOptional(source.accountName())),
            billValue(source.entryType(), normalizeOptional(source.payee())),
            normalizeOptional(source.notes()),
            source.sinkingFundId() == null
                ? sinkingValue(source.entryType(), source.targetMinor())
                : null,
            source.sinkingFundId() == null
                ? sinkingValue(source.entryType(), source.targetDate())
                : null,
            null,
            sinkingValue(source.entryType(), source.sinkingFundId()));
    validateRecurringSource(
        source.entryType(),
        source.sourceRecurringBillDefinitionId(),
        source.sourceRecurringOccurrenceDate());
    entry.setRecurringSource(
        source.sourceRecurringBillDefinitionId(), source.sourceRecurringOccurrenceDate());
    return entry;
  }

  PaycheckEntry leftoverEntry(UUID ownerId, UUID paycheckId, long amountMinor, int position) {
    return new PaycheckEntry(
        ownerId,
        paycheckId,
        EntryType.BILL,
        "LEFTOVER",
        amountMinor,
        position,
        EntryPaymentMethod.AUTOPAY,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  PaycheckEntry newEntry(UUID ownerId, UUID paycheckId, CreateEntryRequest request, int position) {
    return new PaycheckEntry(
        ownerId,
        paycheckId,
        request.entryType(),
        request.name().trim(),
        request.amountMinor(),
        position,
        paymentMethodForCreate(request.entryType(), request.paymentMethod()),
        billValue(request.entryType(), request.dueDate()),
        billValue(request.entryType(), normalizeOptional(request.accountName())),
        billValue(request.entryType(), normalizeOptional(request.payee())),
        normalizeOptional(request.notes()),
        request.sinkingFundId() == null
            ? sinkingValue(request.entryType(), request.targetMinor())
            : null,
        request.sinkingFundId() == null
            ? sinkingValue(request.entryType(), request.targetDate())
            : null,
        request.paybackId(),
        sinkingValue(request.entryType(), request.sinkingFundId()));
  }

  void update(PaycheckEntry entry, UpdateEntryRequest request) {
    entry.update(
        request.entryType(),
        request.name().trim(),
        request.amountMinor(),
        paymentMethodForUpdate(entry, request.entryType(), request.paymentMethod()),
        billValue(request.entryType(), request.dueDate()),
        billValue(request.entryType(), normalizeOptional(request.accountName())),
        billValue(request.entryType(), normalizeOptional(request.payee())),
        normalizeOptional(request.notes()),
        request.sinkingFundId() == null
            ? sinkingValue(request.entryType(), request.targetMinor())
            : null,
        request.sinkingFundId() == null
            ? sinkingValue(request.entryType(), request.targetDate())
            : null,
        request.paybackId(),
        sinkingValue(request.entryType(), request.sinkingFundId()));
  }

  private String normalizeOptional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private <T> T billValue(EntryType type, T value) {
    return type == EntryType.BILL ? value : null;
  }

  private EntryPaymentMethod paymentMethodForCreate(EntryType type, EntryPaymentMethod requested) {
    if (type != EntryType.BILL) {
      if (requested != null) {
        throw new BusinessRuleException("Only Bills can have a payment method.");
      }
      return null;
    }
    return requested == null ? EntryPaymentMethod.AUTOPAY : requested;
  }

  private EntryPaymentMethod paymentMethodForUpdate(
      PaycheckEntry existing, EntryType requestedType, EntryPaymentMethod requested) {
    if (requestedType != EntryType.BILL) {
      if (requested != null) {
        throw new BusinessRuleException("Only Bills can have a payment method.");
      }
      return null;
    }
    if (requested != null) {
      return requested;
    }
    return existing.getEntryType() == EntryType.BILL && existing.getPaymentMethod() != null
        ? existing.getPaymentMethod()
        : EntryPaymentMethod.AUTOPAY;
  }

  private <T> T sinkingValue(EntryType type, T value) {
    return type == EntryType.SINKING_FUND ? value : null;
  }

  private void validateRecurringSource(
      EntryType entryType, UUID definitionId, LocalDate occurrenceDate) {
    if ((definitionId == null) != (occurrenceDate == null)) {
      throw new BusinessRuleException("Recurring Bill provenance must be supplied together.");
    }
    if (definitionId != null && entryType != EntryType.BILL) {
      throw new BusinessRuleException("Only Bills can have recurring provenance.");
    }
  }
}
