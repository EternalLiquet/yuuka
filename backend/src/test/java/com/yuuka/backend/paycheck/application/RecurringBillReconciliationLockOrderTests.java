package com.yuuka.backend.paycheck.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.common.api.BusinessRuleException;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.domain.EntryStatus;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckMetrics;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.recurring.api.dto.CreateRecurringBillFromEntryRequest;
import com.yuuka.backend.recurring.api.dto.LinkRecurringBillRequest;
import com.yuuka.backend.recurring.domain.MonthlyOccurrencePolicy;
import com.yuuka.backend.recurring.infrastructure.JpaRecurringBillDefinitionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RecurringBillReconciliationLockOrderTests {
  private final JpaRecurringBillDefinitionRepository definitions =
      mock(JpaRecurringBillDefinitionRepository.class);
  private final JpaPaycheckRepository paychecks = mock(JpaPaycheckRepository.class);
  private final JpaPaycheckEntryRepository entries = mock(JpaPaycheckEntryRepository.class);
  private final PaybackService paybackService = mock(PaybackService.class);
  private final MonthlyOccurrencePolicy occurrencePolicy = mock(MonthlyOccurrencePolicy.class);
  private final PaycheckResponseAssembler responseAssembler = mock(PaycheckResponseAssembler.class);

  @Test
  void linkLocksAssignedPaybackBeforePaycheckEntryAndDefinition() {
    Fixture fixture = fixture(UUID.randomUUID(), 2);

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ResourceNotFoundException.class);

    InOrder order = inOrder(paybackService, paychecks, entries, definitions);
    order
        .verify(entries)
        .findByIdAndOwnerIdAndDeletedAtIsNull(fixture.entryId(), fixture.ownerId());
    order
        .verify(paybackService)
        .lockForRecurringReconciliation(fixture.ownerId(), fixture.paybackId());
    order.verify(paychecks).findByIdAndOwnerIdForUpdate(fixture.paycheckId(), fixture.ownerId());
    order.verify(entries).findLiveByIdAndOwnerIdForUpdate(fixture.entryId(), fixture.ownerId());
    order
        .verify(definitions)
        .findByIdAndOwnerIdForUpdate(request(2).definitionId(), fixture.ownerId());
  }

  @Test
  void createLocksAssignedPaybackBeforePaycheckAndEntry() {
    Fixture fixture = fixture(UUID.randomUUID(), 2);
    when(occurrencePolicy.occurrence(YearMonth.of(2026, 8), 21))
        .thenReturn(LocalDate.parse("2026-08-21"));
    when(responseAssembler.calculate(anyLong(), any())).thenReturn(mock(PaycheckMetrics.class));
    when(definitions.saveAndFlush(any()))
        .thenThrow(new BusinessRuleException("Stop after proving definition access order."));

    assertThatThrownBy(
            () -> service().createFromEntry(fixture.ownerId(), fixture.entryId(), createRequest()))
        .isInstanceOf(BusinessRuleException.class);

    InOrder order = inOrder(paybackService, paychecks, entries, definitions);
    order
        .verify(entries)
        .findByIdAndOwnerIdAndDeletedAtIsNull(fixture.entryId(), fixture.ownerId());
    order
        .verify(paybackService)
        .lockForRecurringReconciliation(fixture.ownerId(), fixture.paybackId());
    order.verify(paychecks).findByIdAndOwnerIdForUpdate(fixture.paycheckId(), fixture.ownerId());
    order.verify(entries).findLiveByIdAndOwnerIdForUpdate(fixture.entryId(), fixture.ownerId());
    order.verify(definitions).saveAndFlush(any());
  }

  @Test
  void unlinkLocksAssignedPaybackBeforePaycheckAndEntry() {
    Fixture fixture = fixture(UUID.randomUUID(), 2);

    assertThatThrownBy(() -> service().unlink(fixture.ownerId(), fixture.entryId(), 2L, 4L))
        .isInstanceOf(BusinessRuleException.class);

    InOrder order = inOrder(paybackService, paychecks, entries);
    order
        .verify(entries)
        .findByIdAndOwnerIdAndDeletedAtIsNull(fixture.entryId(), fixture.ownerId());
    order
        .verify(paybackService)
        .lockForRecurringReconciliation(fixture.ownerId(), fixture.paybackId());
    order.verify(paychecks).findByIdAndOwnerIdForUpdate(fixture.paycheckId(), fixture.ownerId());
    order.verify(entries).findLiveByIdAndOwnerIdForUpdate(fixture.entryId(), fixture.ownerId());
  }

  @Test
  void allActionsSkipOnlyThePaybackLockWhenEntryHasNoAssignment() {
    Fixture fixture = fixture(null, 2);
    when(occurrencePolicy.occurrence(YearMonth.of(2026, 8), 21))
        .thenReturn(LocalDate.parse("2026-08-20"));

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(
            () -> service().createFromEntry(fixture.ownerId(), fixture.entryId(), createRequest()))
        .isInstanceOf(BusinessRuleException.class);
    assertThatThrownBy(() -> service().unlink(fixture.ownerId(), fixture.entryId(), 2L, 4L))
        .isInstanceOf(BusinessRuleException.class);

    verify(paybackService, never())
        .lockForRecurringReconciliation(fixture.ownerId(), fixture.paybackId());
    verify(paychecks, org.mockito.Mockito.times(3))
        .findByIdAndOwnerIdForUpdate(fixture.paycheckId(), fixture.ownerId());
    verify(entries, org.mockito.Mockito.times(3))
        .findLiveByIdAndOwnerIdForUpdate(fixture.entryId(), fixture.ownerId());
  }

  @Test
  void rejectsPaybackAssignmentChangedAfterDiscovery() {
    UUID discoveredPaybackId = UUID.randomUUID();
    Fixture fixture = fixture(discoveredPaybackId, 2);
    when(fixture.lockedEntry().getPaybackId()).thenReturn(UUID.randomUUID());

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ConflictException.class);

    verify(paybackService).lockForRecurringReconciliation(fixture.ownerId(), discoveredPaybackId);
    verify(definitions, never())
        .findByIdAndOwnerIdForUpdate(request(2).definitionId(), fixture.ownerId());
  }

  @Test
  void rejectsEntryVersionChangedAfterDiscovery() {
    Fixture fixture = fixture(UUID.randomUUID(), 3);

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ConflictException.class);

    verify(definitions, never())
        .findByIdAndOwnerIdForUpdate(request(2).definitionId(), fixture.ownerId());
  }

  @Test
  void rejectsPaycheckVersionChangedBeforeDefinitionLock() {
    Fixture fixture = fixture(UUID.randomUUID(), 2);
    when(fixture.paycheck().getVersion()).thenReturn(5L);

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ConflictException.class);

    verify(definitions, never())
        .findByIdAndOwnerIdForUpdate(request(2).definitionId(), fixture.ownerId());
  }

  @Test
  void staleInactivePaycheckReturnsConflictBeforeStateValidationOrDefinitionLock() {
    Fixture fixture = fixture(UUID.randomUUID(), 2);
    when(fixture.paycheck().getVersion()).thenReturn(5L);
    when(fixture.paycheck().getState()).thenReturn(PaycheckState.CLOSED);

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ConflictException.class);

    verify(definitions, never())
        .findByIdAndOwnerIdForUpdate(request(2).definitionId(), fixture.ownerId());
  }

  @Test
  void rejectsPaycheckChangedAfterDiscoveryBeforeDefinitionLock() {
    Fixture fixture = fixture(UUID.randomUUID(), 2);
    when(fixture.lockedEntry().getPaycheckId()).thenReturn(UUID.randomUUID());

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ConflictException.class);

    verify(definitions, never())
        .findByIdAndOwnerIdForUpdate(request(2).definitionId(), fixture.ownerId());
  }

  private Fixture fixture(UUID paybackId, long lockedEntryVersion) {
    UUID ownerId = UUID.randomUUID();
    UUID paycheckId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();
    PaycheckEntry discoveredEntry = mock(PaycheckEntry.class);
    PaycheckEntry lockedEntry = mock(PaycheckEntry.class);
    Paycheck paycheck = mock(Paycheck.class);
    when(discoveredEntry.getPaycheckId()).thenReturn(paycheckId);
    when(discoveredEntry.getPaybackId()).thenReturn(paybackId);
    when(lockedEntry.getPaycheckId()).thenReturn(paycheckId);
    when(lockedEntry.getPaybackId()).thenReturn(paybackId);
    when(lockedEntry.getId()).thenReturn(entryId);
    when(lockedEntry.getVersion()).thenReturn(lockedEntryVersion);
    when(lockedEntry.getEntryType()).thenReturn(EntryType.BILL);
    when(lockedEntry.getAmountMinor()).thenReturn(1399L);
    when(lockedEntry.getStatus()).thenReturn(EntryStatus.NOT_PAID);
    when(paycheck.getId()).thenReturn(paycheckId);
    when(paycheck.getAmountMinor()).thenReturn(20000L);
    when(paycheck.getVersion()).thenReturn(4L);
    when(paycheck.getState()).thenReturn(PaycheckState.ACTIVE);
    when(entries.findByIdAndOwnerIdAndDeletedAtIsNull(entryId, ownerId))
        .thenReturn(Optional.of(discoveredEntry));
    when(paychecks.findByIdAndOwnerIdForUpdate(paycheckId, ownerId))
        .thenReturn(Optional.of(paycheck));
    when(entries.findLiveByIdAndOwnerIdForUpdate(entryId, ownerId))
        .thenReturn(Optional.of(lockedEntry));
    when(entries.findAllByPaycheckIdAndOwnerIdAndDeletedAtIsNullOrderByPosition(
            paycheckId, ownerId))
        .thenReturn(List.of(lockedEntry));
    when(definitions.findByIdAndOwnerIdForUpdate(request(2).definitionId(), ownerId))
        .thenReturn(Optional.empty());
    return new Fixture(ownerId, paycheckId, entryId, paybackId, paycheck, lockedEntry);
  }

  private LinkRecurringBillRequest request(long entryVersion) {
    return new LinkRecurringBillRequest(
        entryVersion,
        4L,
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        1L,
        LocalDate.parse("2026-08-21"),
        false);
  }

  private CreateRecurringBillFromEntryRequest createRequest() {
    return new CreateRecurringBillFromEntryRequest(
        2L, 4L, "Netflix", 1499L, null, 21, "Visa", "Netflix", null, LocalDate.parse("2026-08-21"));
  }

  private RecurringBillEntryReconciliationService service() {
    return new RecurringBillEntryReconciliationService(
        definitions,
        paychecks,
        entries,
        responseAssembler,
        mock(PaycheckEntryMutationHelper.class),
        mock(PaycheckLifecycleTransitions.class),
        new PaycheckValidationHelper(),
        paybackService,
        occurrencePolicy,
        mock(OwnerLocalDateService.class),
        mock(AuditService.class),
        Clock.systemUTC());
  }

  private record Fixture(
      UUID ownerId,
      UUID paycheckId,
      UUID entryId,
      UUID paybackId,
      Paycheck paycheck,
      PaycheckEntry lockedEntry) {}
}
