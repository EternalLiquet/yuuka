import { filterAndSortEntries } from '@/features/paychecks/entry-list';
import type { Entry } from '@/api/contracts';

const entries: Entry[] = [
  {
    id: '00000000-0000-0000-0000-000000000001',
    paycheckId: '00000000-0000-0000-0000-000000000010',
    paybackId: null,
    entryType: 'BILL',
    name: 'Electricity',
    amountMinor: 13052,
    status: 'PROCESSING',
    position: 1,
    dueDate: '2026-07-12',
    paymentMethod: 'MANUAL',
    accountName: null,
    payee: null,
    notes: null,
    targetMinor: null,
    targetDate: null,
    spentMinor: null,
    remainingMinor: null,
    overBudget: null,
    createdAt: '2026-07-10T12:00:00Z',
    updatedAt: '2026-07-10T13:00:00Z',
    version: 0,
  },
  {
    id: '00000000-0000-0000-0000-000000000002',
    paycheckId: '00000000-0000-0000-0000-000000000010',
    paybackId: null,
    entryType: 'SPENDING_BUCKET',
    name: 'Work Food',
    amountMinor: 5000,
    status: 'NOT_PAID',
    position: 0,
    dueDate: null,
    paymentMethod: null,
    accountName: null,
    payee: null,
    notes: null,
    targetMinor: null,
    targetDate: null,
    spentMinor: 2145,
    remainingMinor: 2855,
    overBudget: false,
    createdAt: '2026-07-10T12:00:00Z',
    updatedAt: '2026-07-10T12:30:00Z',
    version: 0,
  },
];

describe('filterAndSortEntries', () => {
  it('restores saved custom order after a temporary amount sort', () => {
    expect(filterAndSortEntries(entries, { sort: 'amount', direction: 'desc' })[0].name).toBe(
      'Electricity',
    );
    expect(filterAndSortEntries(entries, { sort: 'custom', direction: 'asc' })[0].name).toBe(
      'Work Food',
    );
    expect(entries[0].name).toBe('Electricity');
  });

  it('combines status and type filters', () => {
    expect(
      filterAndSortEntries(entries, {
        sort: 'custom',
        direction: 'asc',
        status: 'NOT_PAID',
        type: 'SPENDING_BUCKET',
      }).map((entry) => entry.name),
    ).toEqual(['Work Food']);
  });

  it.each([
    ['status', 'Work Food'],
    ['due-date', 'Electricity'],
    ['last-edited', 'Work Food'],
  ] as const)('sorts by %s without mutating source order', (sort, firstName) => {
    expect(filterAndSortEntries(entries, { sort, direction: 'asc' })[0].name).toBe(firstName);
    expect(entries.map((entry) => entry.name)).toEqual(['Electricity', 'Work Food']);
  });

  it('reverses a selected sort only when descending is requested', () => {
    expect(filterAndSortEntries(entries, { sort: 'amount', direction: 'asc' })[0].name).toBe(
      'Work Food',
    );
    expect(filterAndSortEntries(entries, { sort: 'amount', direction: 'desc' })[0].name).toBe(
      'Electricity',
    );
  });

  it('filters Bill payment method with status without mutating custom order', () => {
    const autopay = {
      ...entries[0],
      id: '00000000-0000-0000-0000-000000000003',
      paymentMethod: 'AUTOPAY' as const,
      position: 2,
      status: 'NOT_PAID' as const,
      name: 'Internet',
    };
    const source = [...entries, autopay];

    expect(
      filterAndSortEntries(source, {
        sort: 'custom',
        direction: 'asc',
        paymentMethod: 'MANUAL',
        status: 'PROCESSING',
      }).map((entry) => entry.name),
    ).toEqual(['Electricity']);
    expect(
      filterAndSortEntries(source, {
        sort: 'custom',
        direction: 'asc',
        paymentMethod: 'AUTOPAY',
        status: 'NOT_PAID',
      }).map((entry) => entry.name),
    ).toEqual(['Internet']);
    expect(source.map((entry) => entry.name)).toEqual(['Electricity', 'Work Food', 'Internet']);
  });
});
