import type { BudgetTemplate, EntryPaymentMethod, TemplateEntry } from '@/api/contracts';
import type { EntryPayload } from '@/api/use-yuuka-api';

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
    targetDate: entry.targetDate,
    targetMinor: entry.targetMinor,
  };
}

export function draftTotalMinor(entries: TemplateApplicationDraftEntry[]) {
  return entries.reduce((total, entry) => total + entry.amountMinor, 0);
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
      entry.entryType === 'BILL' ? dueDateFromOffset(incomeDate, entry.defaultDueOffsetDays) : null,
    accountName: entry.entryType === 'BILL' ? entry.accountName : null,
    payee: entry.entryType === 'BILL' ? entry.payee : null,
    notes: entry.notes,
    targetMinor: entry.entryType === 'SINKING_FUND' ? entry.targetMinor : null,
    targetDate: entry.entryType === 'SINKING_FUND' ? entry.targetDate : null,
  }));
}
