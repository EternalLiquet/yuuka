import {
  allocationChangeMessage,
  materialRecurringChanges,
  nearbyOccurrenceMonths,
  occurrenceForMonth,
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
