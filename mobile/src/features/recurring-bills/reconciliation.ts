import type { QueryClient } from '@tanstack/react-query';

import type {
  Entry,
  EntryPaymentMethod,
  Paycheck,
  RecurringBill,
  RecurringBillOccurrence,
} from '@/api/contracts';

export type RecurringSnapshot = Pick<
  RecurringBill,
  'accountName' | 'name' | 'notes' | 'payee' | 'paymentMethod' | 'typicalAmountMinor'
>;

export type MaterialChange = {
  after: string;
  before: string;
  field: string;
};

export type RefreshedReconciliationSelection =
  | {
      definition: null;
      kind: 'definition-unavailable';
      message: string;
      occurrence: null;
    }
  | {
      definition: RecurringBill;
      kind: 'occurrence-unavailable';
      message: string;
      occurrence: null;
    }
  | {
      definition: RecurringBill;
      kind: 'ready';
      message: string;
      occurrence: RecurringBillOccurrence;
    };

export function refreshedReconciliationSelection(
  definitions: RecurringBill[],
  occurrences: RecurringBillOccurrence[],
  definitionId: string,
  occurrenceDate: string,
): RefreshedReconciliationSelection {
  const definition = definitions.find((item) => item.id === definitionId);
  if (!definition) {
    return {
      definition: null,
      kind: 'definition-unavailable',
      message: 'That recurring Bill is no longer Active. Choose another recurring Bill.',
      occurrence: null,
    };
  }
  const occurrence = occurrences.find(
    (item) => item.definitionId === definitionId && item.occurrenceDate === occurrenceDate,
  );
  if (!occurrence) {
    return {
      definition,
      kind: 'occurrence-unavailable',
      message: 'That occurrence is no longer available. Choose another occurrence.',
      occurrence: null,
    };
  }
  return {
    definition,
    kind: 'ready',
    message: 'Current data was refreshed. Review the updated values before retrying.',
    occurrence,
  };
}

export async function refreshRecurringReconciliationQueries(
  queryClient: QueryClient,
  paycheckId: string,
  updated: Paycheck,
) {
  queryClient.setQueryData(['paycheck', paycheckId], updated);
  await Promise.all(
    [
      ['paycheck', paycheckId],
      ['paychecks'],
      ['dashboard'],
      ['recurring-bills'],
      ['recurring-bill'],
      ['search', 'entries'],
      ['paybacks'],
      ['payback'],
    ].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
  );
}

export function occurrenceForMonth(monthDate: string, dueDay: number) {
  const [year, month] = monthDate.split('-').map(Number);
  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return `${year.toString().padStart(4, '0')}-${month
    .toString()
    .padStart(2, '0')}-${Math.min(dueDay, lastDay).toString().padStart(2, '0')}`;
}

export function nearbyOccurrenceMonths(anchorDate: string) {
  const [year, month] = anchorDate.split('-').map(Number);
  return [-2, -1, 0, 1, 2].map((offset) => {
    const value = new Date(Date.UTC(year, month - 1 + offset, 1));
    return value.toISOString().slice(0, 10);
  });
}

export function timelineRange(anchorDate: string) {
  const months = nearbyOccurrenceMonths(anchorDate);
  const last = months.at(-1) ?? anchorDate;
  const [year, month] = last.split('-').map(Number);
  return {
    from: months[0],
    through: new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10),
  };
}

export function materialRecurringChanges(
  entry: Entry,
  definition: RecurringSnapshot,
  occurrenceDate: string,
  formatAmount: (amountMinor: number) => string,
): MaterialChange[] {
  const values = [
    ['Name', entry.name, definition.name],
    ['Amount', formatAmount(entry.amountMinor), formatAmount(definition.typicalAmountMinor)],
    ['Due date', entry.dueDate ?? 'None', occurrenceDate],
    [
      'Payment method',
      paymentMethodLabel(entry.paymentMethod),
      paymentMethodLabel(definition.paymentMethod),
    ],
    ['Account', entry.accountName ?? 'None', definition.accountName ?? 'None'],
    ['Payee', entry.payee ?? 'None', definition.payee ?? 'None'],
    ['Notes', entry.notes ?? 'None', definition.notes ?? 'None'],
  ] as const;
  return values
    .filter(([, before, after]) => before !== after)
    .map(([field, before, after]) => ({ after, before, field }));
}

export function allocationChangeMessage(
  entryAmountMinor: number,
  nextAmountMinor: number,
  formatAmount: (amountMinor: number) => string,
) {
  const difference = nextAmountMinor - entryAmountMinor;
  if (difference > 0) return `Uses an additional ${formatAmount(difference)} from this paycheck`;
  if (difference < 0) return `Returns ${formatAmount(Math.abs(difference))} to unallocated money`;
  return 'Allocation does not change';
}

export function paymentMethodLabel(method: EntryPaymentMethod | null) {
  return method === 'MANUAL' ? 'Manual Pay' : 'Autopay';
}
