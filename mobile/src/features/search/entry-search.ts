import { parseMoneyToMinor } from '@/domain/money';

export type EntrySearchMode = 'ALL' | 'NAME' | 'AMOUNT';

export type EntrySearchCriteria =
  | { amountMinor: number; error?: never; query?: never }
  | { amountMinor?: never; error?: never; query: string }
  | { amountMinor?: never; error: string; query?: never };

export function buildEntrySearchCriteria(
  rawQuery: string,
  mode: EntrySearchMode,
): EntrySearchCriteria | null {
  const trimmed = rawQuery.trim();
  if (!trimmed) return null;
  if (mode === 'NAME') return { query: trimmed };
  if (mode === 'AMOUNT') return parseAmountCriteria(trimmed);
  try {
    return { amountMinor: parseMoneyToMinor(trimmed) };
  } catch (error) {
    if (looksLikeAmount(trimmed)) {
      return { error: error instanceof Error ? error.message : 'Enter a valid amount.' };
    }
    return { query: trimmed };
  }
}

function parseAmountCriteria(value: string): EntrySearchCriteria {
  try {
    return { amountMinor: parseMoneyToMinor(value) };
  } catch (error) {
    return { error: error instanceof Error ? error.message : 'Enter a valid amount.' };
  }
}

function looksLikeAmount(value: string) {
  return /^-?\$?[\d,.]+$/.test(value);
}
