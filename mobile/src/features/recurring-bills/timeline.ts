import type { RecurringBillOccurrence } from '@/api/contracts';

export type TimelineDay = { date: string; items: RecurringBillOccurrence[] };

export function groupTimeline(items: RecurringBillOccurrence[], today: string): TimelineDay[] {
  const grouped = new Map<string, RecurringBillOccurrence[]>();
  for (const item of items) {
    const group = grouped.get(item.occurrenceDate) ?? [];
    group.push(item);
    grouped.set(item.occurrenceDate, group);
  }
  if (!grouped.has(today)) grouped.set(today, []);
  return [...grouped.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([date, dateItems]) => ({
      date,
      items: [...dateItems].sort(
        (left, right) =>
          left.name.localeCompare(right.name, undefined, { sensitivity: 'base' }) ||
          left.definitionId.localeCompare(right.definitionId),
      ),
    }));
}
