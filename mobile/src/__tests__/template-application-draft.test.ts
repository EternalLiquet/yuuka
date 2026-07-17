import {
  applicationEntriesFromDraft,
  draftEntriesFromPaycheck,
  draftEntriesFromTemplate,
  draftTotalMinor,
  dueDateFromOffset,
  TemplateApplicationDraftEntry,
} from '@/features/templates/application-draft';

import type { BudgetTemplate, Entry, Paycheck } from '@/api/contracts';

describe('template application draft helpers', () => {
  it('initializes ordered draft entries from template entries', () => {
    const draft = draftEntriesFromTemplate(template());

    expect(draft.map((entry) => entry.name)).toEqual(['Rent', 'Groceries']);
    expect(draft[0]).toEqual(
      expect.objectContaining({
        amountMinor: 110000,
        clientId: 'bill-1',
        paymentMethod: 'MANUAL',
      }),
    );
    expect(draftTotalMinor(draft)).toBe(120000);
  });

  it.each([
    [null, null],
    [0, '2026-07-17'],
    [2, '2026-07-19'],
    [-3, '2026-07-14'],
  ] as const)('converts due offset %s to %s', (offset, expected) => {
    expect(dueDateFromOffset('2026-07-17', offset)).toBe(expected);
  });

  it('maps drafts to application entries with type-specific fields', () => {
    const entries = applicationEntriesFromDraft('2026-07-17', [
      draftEntry({
        accountName: 'Visa',
        defaultDueOffsetDays: 2,
        entryType: 'BILL',
        name: 'Internet',
        paymentMethod: null,
        payee: 'ISP',
        targetDate: '2026-12-01',
        targetMinor: 50000,
      }),
      draftEntry({
        entryType: 'SPENDING_BUCKET',
        name: 'Fuel',
        paymentMethod: 'MANUAL',
        targetDate: '2026-12-01',
        targetMinor: 50000,
      }),
      draftEntry({
        accountName: 'Brokerage',
        entryType: 'SINKING_FUND',
        name: 'Insurance',
        payee: 'Carrier',
        targetDate: '2026-12-01',
        targetMinor: 50000,
      }),
    ]);

    expect(entries).toEqual([
      expect.objectContaining({
        accountName: 'Visa',
        dueDate: '2026-07-19',
        name: 'Internet',
        paymentMethod: 'AUTOPAY',
        targetDate: null,
        targetMinor: null,
      }),
      expect.objectContaining({
        accountName: null,
        name: 'Fuel',
        paymentMethod: null,
        targetDate: null,
        targetMinor: null,
      }),
      expect.objectContaining({
        accountName: null,
        name: 'Insurance',
        paymentMethod: null,
        payee: null,
        targetDate: '2026-12-01',
        targetMinor: 50000,
      }),
    ]);
  });

  it('initializes duplicate draft entries from a paycheck without copied state', () => {
    const draft = draftEntriesFromPaycheck(paycheck());

    expect(draft.omittedLeftoverCount).toBe(1);
    expect(draft.clearedPaybackCount).toBe(1);
    expect(draft.entries.map((entry) => entry.name)).toEqual(['Rent', 'Groceries', 'Insurance']);
    expect(draft.entries[0]).toEqual(
      expect.objectContaining({
        amountMinor: 94000,
        clientId: 'paycheck-entry-bill',
        defaultDueOffsetDays: 6,
        paymentMethod: 'MANUAL',
      }),
    );
    expect(applicationEntriesFromDraft('2026-07-16', draft.entries)[0]).toEqual(
      expect.objectContaining({
        dueDate: '2026-07-22',
        name: 'Rent',
        paymentMethod: 'MANUAL',
      }),
    );
    expect(draftTotalMinor(draft.entries)).toBe(119000);
  });
});

function draftEntry(
  overrides: Partial<TemplateApplicationDraftEntry> = {},
): TemplateApplicationDraftEntry {
  return {
    accountName: null,
    amountMinor: 2500,
    clientId: 'draft-1',
    defaultDueOffsetDays: null,
    entryType: 'BILL',
    name: 'Draft',
    notes: null,
    payee: null,
    paymentMethod: 'MANUAL',
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    targetDate: null,
    targetMinor: null,
    ...overrides,
  };
}

function template(): BudgetTemplate {
  return {
    archived: false,
    archivedAt: null,
    createdAt: '2026-07-12T12:00:00Z',
    defaultTotalMinor: 120000,
    description: null,
    entries: [
      {
        accountName: null,
        createdAt: '2026-07-12T12:00:00Z',
        defaultAmountMinor: 10000,
        defaultDueOffsetDays: null,
        entryType: 'SPENDING_BUCKET',
        id: 'bucket-1',
        name: 'Groceries',
        notes: null,
        payee: null,
        paymentMethod: null,
        position: 1,
        targetDate: null,
        targetMinor: null,
        updatedAt: '2026-07-13T12:00:00Z',
        version: 1,
      },
      {
        accountName: null,
        createdAt: '2026-07-12T12:00:00Z',
        defaultAmountMinor: 110000,
        defaultDueOffsetDays: null,
        entryType: 'BILL',
        id: 'bill-1',
        name: 'Rent',
        notes: null,
        payee: null,
        paymentMethod: 'MANUAL',
        position: 0,
        targetDate: null,
        targetMinor: null,
        updatedAt: '2026-07-13T12:00:00Z',
        version: 1,
      },
    ],
    entryCount: 2,
    id: 'template-1',
    name: 'Rent 1',
    updatedAt: '2026-07-13T12:00:00Z',
    version: 7,
  };
}

function paycheck(): Paycheck {
  return {
    allocatedMinor: 120000,
    allocationPercent: 100,
    amountMinor: 120000,
    archivedAt: null,
    closedAt: '2026-07-08T12:00:00Z',
    completionPercent: 100,
    createdAt: '2026-07-02T12:00:00Z',
    entries: [
      paycheckEntry({
        amountMinor: 1000,
        id: 'entry-leftover',
        name: 'LEFTOVER',
        position: 3,
      }),
      paycheckEntry({
        amountMinor: 10000,
        entryType: 'SPENDING_BUCKET',
        id: 'entry-bucket',
        name: 'Groceries',
        paymentMethod: null,
        position: 1,
        remainingMinor: 2500,
        spentMinor: 7500,
      }),
      paycheckEntry({
        amountMinor: 15000,
        entryType: 'SINKING_FUND',
        id: 'entry-fund',
        name: 'Insurance',
        paymentMethod: null,
        paybackId: '11111111-1111-4111-8111-111111111777',
        position: 2,
        targetDate: '2026-12-01',
        targetMinor: 120000,
      }),
      paycheckEntry({
        accountName: 'Checking',
        amountMinor: 94000,
        dueDate: '2026-07-08',
        id: 'entry-bill',
        name: 'Rent',
        payee: 'Apartment',
        paymentMethod: 'MANUAL',
        position: 0,
      }),
    ],
    id: '11111111-1111-4111-8111-111111111100',
    incomeDate: '2026-07-02',
    name: 'Rent 1/2',
    notPaidCount: 0,
    notPaidMinor: 0,
    notes: 'Original notes',
    postedCount: 4,
    postedMinor: 120000,
    processingCount: 0,
    processingMinor: 0,
    reopenedAt: null,
    requiresAttention: false,
    source: 'Employer',
    spendingBucketPerformance: null,
    state: 'CLOSED',
    templateSourceId: null,
    unallocatedMinor: 0,
    updatedAt: '2026-07-08T12:00:00Z',
    version: 4,
  };
}

function paycheckEntry(overrides: Partial<Entry>): Entry {
  return {
    accountName: null,
    amountMinor: 1000,
    createdAt: '2026-07-02T12:00:00Z',
    dueDate: null,
    entryType: 'BILL',
    id: 'entry',
    name: 'Entry',
    notes: null,
    overBudget: null,
    paybackId: null,
    payee: null,
    paycheckId: '11111111-1111-4111-8111-111111111100',
    paymentMethod: 'AUTOPAY',
    position: 0,
    remainingMinor: null,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    spentMinor: null,
    status: 'POSTED',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-07-08T12:00:00Z',
    version: 1,
    ...overrides,
  };
}
