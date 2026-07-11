import type { Entry, EntryStatus, EntryType } from '@/api/contracts';

export type EntrySort = 'amount' | 'custom' | 'due-date' | 'last-edited' | 'status';

export type EntryListOptions = {
  direction: 'asc' | 'desc';
  sort: EntrySort;
  status?: EntryStatus;
  type?: EntryType;
};

const statusOrder: Record<EntryStatus, number> = {
  NOT_PAID: 0,
  PROCESSING: 1,
  POSTED: 2,
};

export function filterAndSortEntries(source: readonly Entry[], options: EntryListOptions): Entry[] {
  const filtered = source.filter(
    (entry) =>
      (!options.status || entry.status === options.status) &&
      (!options.type || entry.entryType === options.type),
  );
  const direction = options.direction === 'asc' ? 1 : -1;
  return [...filtered].sort((left, right) => {
    const result = compare(left, right, options.sort);
    return result === 0 ? left.position - right.position : result * direction;
  });
}

function compare(left: Entry, right: Entry, sort: EntrySort): number {
  switch (sort) {
    case 'amount':
      return left.amountMinor - right.amountMinor;
    case 'status':
      return statusOrder[left.status] - statusOrder[right.status];
    case 'due-date':
      return nullableString(left.dueDate).localeCompare(nullableString(right.dueDate));
    case 'last-edited':
      return left.updatedAt.localeCompare(right.updatedAt);
    case 'custom':
      return left.position - right.position;
  }
}

function nullableString(value: string | null): string {
  return value ?? '9999-12-31';
}
