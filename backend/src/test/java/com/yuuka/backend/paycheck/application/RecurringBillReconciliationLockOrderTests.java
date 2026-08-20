package com.yuuka.backend.paycheck.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yuuka.backend.audit.application.AuditService;
import com.yuuka.backend.auth.application.OwnerLocalDateService;
import com.yuuka.backend.common.api.ConflictException;
import com.yuuka.backend.common.api.ResourceNotFoundException;
import com.yuuka.backend.payback.application.PaybackService;
import com.yuuka.backend.paycheck.domain.EntryType;
import com.yuuka.backend.paycheck.domain.Paycheck;
import com.yuuka.backend.paycheck.domain.PaycheckEntry;
import com.yuuka.backend.paycheck.domain.PaycheckState;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckEntryRepository;
import com.yuuka.backend.paycheck.infrastructure.JpaPaycheckRepository;
import com.yuuka.backend.recurring.api.dto.LinkRecurringBillRequest;
import com.yuuka.backend.recurring.domain.MonthlyOccurrencePolicy;
import com.yuuka.backend.recurring.infrastructure.JpaRecurringBillDefinitionRepository;
import java.time.Clock;
import java.time.LocalDate;
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

  @Test
  void locksAssignedPaybackBeforePaycheckEntryAndDefinition() {
    Fixture fixture = fixture(UUID.randomUUID(), 2);

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ResourceNotFoundException.class);

    InOrder order = inOrder(paybackService, paychecks, entries, definitions);
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
  void skipsPaybackLockWhenEntryHasNoAssignment() {
    Fixture fixture = fixture(null, 2);

    assertThatThrownBy(() -> service().link(fixture.ownerId(), fixture.entryId(), request(2)))
        .isInstanceOf(ResourceNotFoundException.class);

    verify(paybackService, never())
        .lockForRecurringReconciliation(fixture.ownerId(), fixture.paybackId());
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
    when(lockedEntry.getVersion()).thenReturn(lockedEntryVersion);
    when(lockedEntry.getEntryType()).thenReturn(EntryType.BILL);
    when(paycheck.getId()).thenReturn(paycheckId);
    when(paycheck.getVersion()).thenReturn(4L);
    when(paycheck.getState()).thenReturn(PaycheckState.ACTIVE);
    when(entries.findByIdAndOwnerIdAndDeletedAtIsNull(entryId, ownerId))
        .thenReturn(Optional.of(discoveredEntry));
    when(paychecks.findByIdAndOwnerIdForUpdate(paycheckId, ownerId))
        .thenReturn(Optional.of(paycheck));
    when(entries.findLiveByIdAndOwnerIdForUpdate(entryId, ownerId))
        .thenReturn(Optional.of(lockedEntry));
    when(definitions.findByIdAndOwnerIdForUpdate(request(2).definitionId(), ownerId))
        .thenReturn(Optional.empty());
    return new Fixture(ownerId, paycheckId, entryId, paybackId, lockedEntry);
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

  private RecurringBillEntryReconciliationService service() {
    return new RecurringBillEntryReconciliationService(
        definitions,
        paychecks,
        entries,
        mock(PaycheckResponseAssembler.class),
        mock(PaycheckEntryMutationHelper.class),
        mock(PaycheckLifecycleTransitions.class),
        new PaycheckValidationHelper(),
        paybackService,
        mock(MonthlyOccurrencePolicy.class),
        mock(OwnerLocalDateService.class),
        mock(AuditService.class),
        Clock.systemUTC());
  }

  private record Fixture(
      UUID ownerId, UUID paycheckId, UUID entryId, UUID paybackId, PaycheckEntry lockedEntry) {}
}
