import type {
  BudgetTemplate,
  Entry,
  EntryPaymentMethod,
  Paycheck,
  TemplateEntry,
} from '@/api/contracts';
import type { EntryPayload } from '@/api/use-yuuka-api';
import type { RecurringBillImportSelection } from '@/features/recurring-bills/import-recurring-bills-sheet';

export type TemplateApplicationDraftEntry = {
  accountName: string | null;
  amountMinor: number;
  clientId: string;
  defaultDueOffsetDays: number | null;
  entryType: 'BILL' | 'SPENDING_BUCKET' | 'SINKING_FUND';
  name: string;
  notes: string | null;
  payee: string | null;
  paymentMethod: EntryPaymentMethod | null;
  sourceRecurringBillDefinitionId: string | null;
  sourceRecurringOccurrenceDate: string | null;
  targetDate: string | null;
  targetMinor: number | null;
};

export function draftEntriesFromTemplate(
  template: BudgetTemplate,
): TemplateApplicationDraftEntry[] {
  return [...template.entries]
    .sort((left, right) => left.position - right.position)
    .map(draftEntryFromTemplateEntry);
}

export function draftEntriesFromPaycheck(paycheck: Paycheck): {
  clearedPaybackCount: number;
  entries: TemplateApplicationDraftEntry[];
  omittedLeftoverCount: number;
} {
  const liveEntries = [...paycheck.entries].sort((left, right) => left.position - right.position);
  const duplicateEntries = liveEntries.filter((entry) => !isGeneratedLeftover(entry));
  return {
    clearedPaybackCount: duplicateEntries.filter((entry) => entry.paybackId != null).length,
    entries: duplicateEntries.map((entry) =>
      draftEntryFromPaycheckEntry(entry, paycheck.incomeDate),
    ),
    omittedLeftoverCount: liveEntries.length - duplicateEntries.length,
  };
}

export function draftEntryFromTemplateEntry(entry: TemplateEntry): TemplateApplicationDraftEntry {
  return {
    accountName: entry.accountName,
    amountMinor: entry.defaultAmountMinor,
    clientId: entry.id,
    defaultDueOffsetDays: entry.defaultDueOffsetDays,
    entryType: entry.entryType,
    name: entry.name,
    notes: entry.notes,
    payee: entry.payee,
    paymentMethod: entry.paymentMethod,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    targetDate: entry.targetDate,
    targetMinor: entry.targetMinor,
  };
}

export function draftEntryFromPaycheckEntry(
  entry: Entry,
  sourceIncomeDate: string,
): TemplateApplicationDraftEntry {
  return {
    accountName: entry.entryType === 'BILL' ? entry.accountName : null,
    amountMinor: entry.amountMinor,
    clientId: `paycheck-${entry.id}`,
    defaultDueOffsetDays:
      entry.entryType === 'BILL' && entry.dueDate
        ? daysBetween(sourceIncomeDate, entry.dueDate)
        : null,
    entryType: entry.entryType,
    name: entry.name,
    notes: entry.notes,
    payee: entry.entryType === 'BILL' ? entry.payee : null,
    paymentMethod: entry.entryType === 'BILL' ? entry.paymentMethod : null,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    targetDate: entry.entryType === 'SINKING_FUND' ? entry.targetDate : null,
    targetMinor: entry.entryType === 'SINKING_FUND' ? entry.targetMinor : null,
  };
}

export function draftTotalMinor(entries: TemplateApplicationDraftEntry[]) {
  return entries.reduce((total, entry) => total + entry.amountMinor, 0);
}

export function draftEntriesFromRecurringBills(
  selections: RecurringBillImportSelection[],
): TemplateApplicationDraftEntry[] {
  const batch = Date.now();
  return selections.map((item, index) => ({
    accountName: item.accountName,
    amountMinor: item.amountMinor,
    clientId: `recurring-${item.definitionId}-${item.occurrenceDate}-${batch}-${index}`,
    defaultDueOffsetDays: null,
    entryType: 'BILL',
    name: item.name,
    notes: item.notes,
    payee: item.payee,
    paymentMethod: item.paymentMethod,
    sourceRecurringBillDefinitionId: item.definitionId,
    sourceRecurringOccurrenceDate: item.occurrenceDate,
    targetDate: null,
    targetMinor: null,
  }));
}

export function dueDateFromOffset(incomeDate: string, offset: number | null): string | null {
  if (offset == null) return null;

  const date = new Date(`${incomeDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + offset);
  return date.toISOString().slice(0, 10);
}

export function applicationEntriesFromDraft(
  incomeDate: string,
  entries: TemplateApplicationDraftEntry[],
): EntryPayload[] {
  return entries.map((entry) => ({
    entryType: entry.entryType,
    name: entry.name,
    amountMinor: entry.amountMinor,
    paymentMethod: entry.entryType === 'BILL' ? (entry.paymentMethod ?? 'AUTOPAY') : null,
    dueDate:
      entry.entryType === 'BILL'
        ? entry.defaultDueOffsetDays == null
          ? entry.sourceRecurringOccurrenceDate
          : dueDateFromOffset(incomeDate, entry.defaultDueOffsetDays)
        : null,
    accountName: entry.entryType === 'BILL' ? entry.accountName : null,
    payee: entry.entryType === 'BILL' ? entry.payee : null,
    notes: entry.notes,
    targetMinor: entry.entryType === 'SINKING_FUND' ? entry.targetMinor : null,
    targetDate: entry.entryType === 'SINKING_FUND' ? entry.targetDate : null,
    sourceRecurringBillDefinitionId:
      entry.entryType === 'BILL' ? entry.sourceRecurringBillDefinitionId : null,
    sourceRecurringOccurrenceDate:
      entry.entryType === 'BILL' ? entry.sourceRecurringOccurrenceDate : null,
  }));
}

function isGeneratedLeftover(entry: Entry) {
  return entry.entryType === 'BILL' && entry.name.trim().toUpperCase() === 'LEFTOVER';
}

function daysBetween(start: string, end: string) {
  const startDate = new Date(`${start}T00:00:00Z`);
  const endDate = new Date(`${end}T00:00:00Z`);
  return Math.round((endDate.getTime() - startDate.getTime()) / 86_400_000);
}
