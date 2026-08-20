import {
  allocationChangeMessage,
  classifyReconciliationResult,
  materialRecurringChanges,
  nearbyOccurrenceMonths,
  occurrenceForMonth,
  refreshedReconciliationSelection,
  refreshRecurringReconciliationQueries,
  timelineRange,
} from '@/features/recurring-bills/reconciliation';

import { QueryClient } from '@tanstack/react-query';
import type { Entry, Paycheck } from '@/api/contracts';

describe('recurring Bill reconciliation helpers', () => {
  it('derives clamped monthly occurrences and a nearby five-month range', () => {
    expect(occurrenceForMonth('2028-02-01', 31)).toBe('2028-02-29');
    expect(occurrenceForMonth('2027-02-18', 31)).toBe('2027-02-28');
    expect(nearbyOccurrenceMonths('2026-08-18')).toEqual([
      '2026-06-01',
      '2026-07-01',
      '2026-08-01',
      '2026-09-01',
      '2026-10-01',
    ]);
    expect(timelineRange('2026-08-18')).toEqual({
      from: '2026-06-01',
      through: '2026-10-31',
    });
  });

  it('shows only material snapshot changes and allocation direction', () => {
    const changes = materialRecurringChanges(
      entry(),
      {
        accountName: 'Visa',
        name: 'Netflix',
        notes: 'Streaming',
        payee: 'Netflix Inc',
        paymentMethod: 'AUTOPAY',
        typicalAmountMinor: 1499,
      },
      '2026-08-21',
      (value) => `$${(value / 100).toFixed(2)}`,
    );

    expect(changes.map((change) => change.field)).toEqual([
      'Name',
      'Amount',
      'Due date',
      'Payment method',
      'Payee',
    ]);
    expect(allocationChangeMessage(1399, 1499, money)).toBe(
      'Uses an additional $1.00 from this paycheck',
    );
    expect(allocationChangeMessage(1499, 1399, money)).toBe('Returns $1.00 to unallocated money');
    expect(allocationChangeMessage(1399, 1399, money)).toBe('Allocation does not change');
  });

  it('refreshes every affected query family after reconciliation', async () => {
    const client = new QueryClient();
    const invalidate = jest.spyOn(client, 'invalidateQueries').mockResolvedValue(undefined);
    const updated = {
      entries: [],
      id: '22222222-2222-4222-8222-222222222222',
    } as unknown as Paycheck;

    await refreshRecurringReconciliationQueries(client, updated.id, updated);

    expect(client.getQueryData(['paycheck', updated.id])).toBe(updated);
    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['paycheck', updated.id],
      ['paychecks'],
      ['dashboard'],
      ['recurring-bills'],
      ['recurring-bill'],
      ['search', 'entries'],
      ['paybacks'],
      ['payback'],
    ]);
  });

  it('clears unavailable definitions and occurrences during stale recovery', () => {
    const currentDefinition = recurringDefinition();
    const currentOccurrence = recurringOccurrence();

    expect(
      refreshedReconciliationSelection(
        [],
        [currentOccurrence],
        currentDefinition.id,
        currentOccurrence.occurrenceDate,
      ),
    ).toMatchObject({ definition: null, kind: 'definition-unavailable', occurrence: null });
    expect(
      refreshedReconciliationSelection(
        [currentDefinition],
        [],
        currentDefinition.id,
        currentOccurrence.occurrenceDate,
      ),
    ).toMatchObject({
      definition: currentDefinition,
      kind: 'occurrence-unavailable',
      occurrence: null,
    });
    expect(
      refreshedReconciliationSelection(
        [currentDefinition],
        [currentOccurrence],
        currentDefinition.id,
        currentOccurrence.occurrenceDate,
      ),
    ).toMatchObject({
      definition: currentDefinition,
      kind: 'ready',
      occurrence: currentOccurrence,
    });
  });

  it('classifies authoritative link unlink and normalized create outcomes by exact attempt', () => {
    const source = entry();
    const linked = {
      ...source,
      sourceRecurringBillDefinitionId: recurringDefinition().id,
      sourceRecurringOccurrenceDate: '2026-08-21',
    };
    const base = {
      baselineEntryVersion: 0,
      baselinePaycheckVersion: 3,
      entryId: source.id,
      paycheckId: source.paycheckId,
    };

    expect(
      classifyReconciliationResult(paycheck(linked), {
        ...base,
        action: 'link',
        baselineDefinitionId: null,
        baselineOccurrenceDate: null,
        confirmDuplicateOccurrence: false,
        definitionId: recurringDefinition().id,
        definitionVersion: 3,
        occurrenceDate: '2026-08-21',
      }),
    ).toMatchObject({ kind: 'succeeded' });
    expect(
      classifyReconciliationResult(paycheck(source), {
        ...base,
        action: 'unlink',
        previousDefinitionId: recurringDefinition().id,
        previousOccurrenceDate: '2026-08-21',
      }),
    ).toMatchObject({ kind: 'succeeded' });
    expect(
      classifyReconciliationResult(
        paycheck({
          ...linked,
          accountName: 'Visa',
          amountMinor: 1499,
          dueDate: '2026-08-21',
          name: 'Netflix',
          notes: null,
          payee: 'Netflix Inc',
          paymentMethod: 'AUTOPAY',
        }),
        {
          ...base,
          action: 'create',
          baselineDefinitionId: null,
          occurrenceDate: '2026-08-21',
          reviewedDefinitionValues: {
            accountName: ' Visa ',
            dueDay: 21,
            name: ' Netflix ',
            notes: ' ',
            payee: ' Netflix Inc ',
            typicalAmountMinor: 1499,
          },
        },
      ),
    ).toMatchObject({ kind: 'succeeded' });
  });

  it('does not treat a different concurrent recurring relationship as the attempted result', () => {
    const source = entry();
    const result = classifyReconciliationResult(
      paycheck({
        ...source,
        sourceRecurringBillDefinitionId: '44444444-4444-4444-8444-444444444444',
        sourceRecurringOccurrenceDate: '2026-09-21',
      }),
      {
        action: 'unlink',
        baselineEntryVersion: 0,
        baselinePaycheckVersion: 3,
        entryId: source.id,
        paycheckId: source.paycheckId,
        previousDefinitionId: recurringDefinition().id,
        previousOccurrenceDate: '2026-08-21',
      },
    );

    expect(result.kind).toBe('different-state');
  });

  it('distinguishes an unchanged baseline link from a different concurrent link', () => {
    const source = {
      ...entry(),
      sourceRecurringBillDefinitionId: recurringDefinition().id,
      sourceRecurringOccurrenceDate: '2026-08-21',
    };
    const attempt = {
      action: 'link' as const,
      baselineDefinitionId: source.sourceRecurringBillDefinitionId,
      baselineEntryVersion: 0,
      baselineOccurrenceDate: source.sourceRecurringOccurrenceDate,
      baselinePaycheckVersion: 3,
      confirmDuplicateOccurrence: false,
      definitionId: '55555555-5555-4555-8555-555555555555',
      definitionVersion: 4,
      entryId: source.id,
      occurrenceDate: '2026-09-15',
      paycheckId: source.paycheckId,
    };

    expect(classifyReconciliationResult(paycheck(source), attempt).kind).toBe('not-applied');
    expect(
      classifyReconciliationResult(
        paycheck({
          ...source,
          sourceRecurringBillDefinitionId: '66666666-6666-4666-8666-666666666666',
          sourceRecurringOccurrenceDate: '2026-10-01',
        }),
        attempt,
      ).kind,
    ).toBe('different-state');
  });
});

function money(value: number) {
  return `$${(value / 100).toFixed(2)}`;
}

function entry(): Entry {
  return {
    accountName: 'Visa',
    amountMinor: 1399,
    createdAt: '2026-08-01T12:00:00Z',
    dueDate: '2026-08-18',
    entryType: 'BILL',
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Netflix Subscription',
    notes: 'Streaming',
    overBudget: null,
    paybackId: null,
    payee: 'Old payee',
    paycheckId: '22222222-2222-4222-8222-222222222222',
    paymentMethod: 'MANUAL',
    position: 0,
    remainingMinor: null,
    sinkingFundId: null,
    sourceExpenseLedgerId: null,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    spentMinor: null,
    status: 'NOT_PAID',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-08-01T12:00:00Z',
    version: 0,
  };
}

function recurringDefinition() {
  return {
    accountName: 'Visa',
    active: true,
    createdAt: '2026-08-01T12:00:00Z',
    dueDay: 21,
    id: '33333333-3333-4333-8333-333333333333',
    name: 'Netflix',
    notes: 'Streaming',
    payee: 'Netflix Inc',
    paymentMethod: 'AUTOPAY' as const,
    recurrenceType: 'MONTHLY' as const,
    typicalAmountMinor: 1499,
    updatedAt: '2026-08-01T12:00:00Z',
    version: 3,
  };
}

function recurringOccurrence() {
  return {
    accountName: 'Visa',
    definitionId: recurringDefinition().id,
    definitionVersion: 3,
    importCount: 0,
    imports: [],
    name: 'Netflix',
    notes: 'Streaming',
    occurrenceDate: '2026-08-21',
    payee: 'Netflix Inc',
    paymentMethod: 'AUTOPAY' as const,
    typicalAmountMinor: 1499,
  };
}

function paycheck(currentEntry: Entry): Paycheck {
  return { entries: [currentEntry], id: currentEntry.paycheckId } as Paycheck;
}
