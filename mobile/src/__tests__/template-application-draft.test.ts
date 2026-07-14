import {
  applicationEntriesFromDraft,
  draftEntriesFromTemplate,
  draftTotalMinor,
  dueDateFromOffset,
  TemplateApplicationDraftEntry,
} from '@/features/templates/application-draft';

import type { BudgetTemplate } from '@/api/contracts';

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
