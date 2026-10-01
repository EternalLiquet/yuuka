package com.yuuka.backend.paycheck.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuuka.backend.recurring.domain.RecurringBillAmountMode;
import com.yuuka.backend.recurring.domain.RecurringBillDefinition;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecurringEstimateTests {
  @Test
  void fixedToVariableNeverTurnsDormantTypicalAmountIntoAnEstimate() {
    RecurringBillDefinition definition =
        new RecurringBillDefinition(
            UUID.randomUUID(),
            "Power",
            RecurringBillAmountMode.FIXED,
            15000L,
            EntryPaymentMethod.AUTOPAY,
            31,
            null,
            null,
            null);
    definition.update(
        "Power",
        RecurringBillAmountMode.VARIABLE,
        null,
        EntryPaymentMethod.AUTOPAY,
        31,
        null,
        null,
        null);
    assertThat(definition.getTypicalAmountMinor()).isEqualTo(15000L);
    assertThat(definition.getPlanningAmountMinor()).isNull();
    definition.setPlanningAmountMinor(0L);
    assertThat(definition.getPlanningAmountMinor()).isZero();
    assertThatThrownBy(() -> definition.setPlanningAmountMinor(-1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ordinaryBillEditsKeepEstimateAndEqualActualClearsOnlyTheMarker() {
    PaycheckEntry entry =
        new PaycheckEntry(
            UUID.randomUUID(),
            UUID.randomUUID(),
            EntryType.BILL,
            "Power",
            15000,
            2,
            EntryPaymentMethod.MANUAL,
            LocalDate.of(2028, 2, 29),
            "Account",
            "Payee",
            "Note",
            null,
            null,
            null,
            null);
    UUID definitionId = UUID.randomUUID();
    entry.setRecurringSource(definitionId, LocalDate.of(2028, 2, 29));
    entry.setAmountEstimated(true);
    entry.transitionTo(EntryStatus.PROCESSING);
    entry.update(
        EntryType.BILL,
        "My power",
        12000,
        EntryPaymentMethod.MANUAL,
        LocalDate.of(2028, 2, 29),
        "Account",
        "Payee",
        "My note",
        null,
        null,
        null,
        null);
    assertThat(entry.isAmountEstimated()).isTrue();
    entry.recordActualAmount(12000);
    assertThat(entry.isAmountEstimated()).isFalse();
    assertThat(entry.getAmountMinor()).isEqualTo(12000);
    assertThat(entry.getName()).isEqualTo("My power");
    assertThat(entry.getNotes()).isEqualTo("My note");
    assertThat(entry.getPosition()).isEqualTo(2);
    assertThat(entry.getStatus()).isEqualTo(EntryStatus.PROCESSING);
    assertThat(entry.getSourceRecurringBillDefinitionId()).isEqualTo(definitionId);
    assertThat(entry.getSourceRecurringOccurrenceDate()).isEqualTo(LocalDate.of(2028, 2, 29));
  }
}
