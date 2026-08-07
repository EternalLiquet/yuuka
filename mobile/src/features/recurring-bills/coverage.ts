import type { EntryStatus, RecurringBillOccurrence } from '@/api/contracts';

export function statusLabel(status: EntryStatus) {
  switch (status) {
    case 'NOT_PAID':
      return 'Not Paid';
    case 'PROCESSING':
      return 'Processing';
    case 'POSTED':
      return 'Posted';
  }
}

export function recurringCoverageLabel(item: RecurringBillOccurrence) {
  if (item.imports.length === 0) return 'Not added to a paycheck';
  if (item.imports.length === 1) {
    const imported = item.imports[0];
    return `Added to ${imported.paycheckName} · ${statusLabel(imported.status)}`;
  }
  return `Added to ${item.imports.length} paychecks · Review imports`;
}

export function recurringCoverageAction(item: RecurringBillOccurrence) {
  if (item.imports.length === 0) return 'Choose an Active paycheck';
  if (item.imports.length === 1) return 'Opens the imported entry in its paycheck';
  return 'Opens all paycheck imports for review';
}
