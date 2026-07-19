import type { InfiniteData } from '@tanstack/react-query';

import type { RecurringBillOccurrence, RecurringBillTimeline } from '@/api/contracts';

export type TimelineDay = { date: string; items: RecurringBillOccurrence[] };
export type TimelineRange = { from: string; through: string };
export type TimelineInfiniteData = InfiniteData<RecurringBillTimeline, TimelineRange>;

export function timelineBounds(today: string): TimelineRange {
  return {
    from: startOfMonth(addCalendarMonths(today, -2)),
    through: endOfMonth(addCalendarMonths(today, 2)),
  };
}

export function initialTimelineRange(today: string): TimelineRange {
  const currentMonthFrom = startOfMonth(today);
  const currentMonthThrough = endOfMonth(today);
  const todayDay = dayOfMonth(today);
  const daysInCurrentMonth = dayOfMonth(currentMonthThrough);

  return clampTimelineRange(
    {
      from: todayDay <= 7 ? addCalendarDays(currentMonthFrom, -7) : currentMonthFrom,
      through:
        todayDay >= daysInCurrentMonth - 6
          ? addCalendarDays(currentMonthThrough, 7)
          : currentMonthThrough,
    },
    timelineBounds(today),
  );
}

export function previousTimelineRange(
  earliestLoadedDate: string,
  today: string,
): TimelineRange | undefined {
  const bounds = timelineBounds(today);
  const through = addCalendarDays(earliestLoadedDate, -1);
  if (through < bounds.from) return undefined;
  return clampTimelineRange({ from: startOfMonth(through), through }, bounds);
}

export function nextTimelineRange(
  latestLoadedDate: string,
  today: string,
): TimelineRange | undefined {
  const bounds = timelineBounds(today);
  const from = addCalendarDays(latestLoadedDate, 1);
  if (from > bounds.through) return undefined;
  return clampTimelineRange({ from, through: endOfMonth(from) }, bounds);
}

function clampTimelineRange(range: TimelineRange, bounds: TimelineRange): TimelineRange {
  return {
    from: maxDate(range.from, bounds.from),
    through: minDate(range.through, bounds.through),
  };
}

function maxDate(left: string, right: string) {
  return left > right ? left : right;
}

function minDate(left: string, right: string) {
  return left < right ? left : right;
}

function addCalendarMonths(date: string, months: number) {
  const value = parseDate(date);
  return formatDate(new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth() + months, 1)));
}

export function timelineContainsDate(
  pages: Pick<RecurringBillTimeline, 'from' | 'through'>[],
  date: string,
) {
  return pages.some((page) => page.from <= date && date <= page.through);
}

export function insertTimelinePage(
  data: TimelineInfiniteData,
  page: RecurringBillTimeline,
): TimelineInfiniteData {
  if (data.pages.some((item) => item.from === page.from && item.through === page.through)) {
    return data;
  }

  const pages = [...data.pages, page].sort((left, right) => left.from.localeCompare(right.from));
  return {
    pageParams: pages.map(({ from, through }) => ({ from, through })),
    pages,
  };
}

export function groupTimeline(items: RecurringBillOccurrence[], today: string): TimelineDay[] {
  const grouped = new Map<string, Map<string, RecurringBillOccurrence>>();
  for (const item of items) {
    const group = grouped.get(item.occurrenceDate) ?? new Map<string, RecurringBillOccurrence>();
    group.set(item.definitionId, item);
    grouped.set(item.occurrenceDate, group);
  }
  if (!grouped.has(today)) grouped.set(today, new Map());
  return [...grouped.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([date, dateItems]) => ({
      date,
      items: [...dateItems.values()].sort(
        (left, right) =>
          left.name.localeCompare(right.name, undefined, { sensitivity: 'base' }) ||
          left.definitionId.localeCompare(right.definitionId),
      ),
    }));
}

function addCalendarDays(date: string, days: number) {
  const value = parseDate(date);
  value.setUTCDate(value.getUTCDate() + days);
  return formatDate(value);
}

function dayOfMonth(date: string) {
  return parseDate(date).getUTCDate();
}

function endOfMonth(date: string) {
  const value = parseDate(date);
  return formatDate(new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth() + 1, 0)));
}

function startOfMonth(date: string) {
  const value = parseDate(date);
  return formatDate(new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth(), 1)));
}

function parseDate(date: string) {
  const [year, month, day] = date.split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day));
}

function formatDate(date: Date) {
  return date.toISOString().slice(0, 10);
}
