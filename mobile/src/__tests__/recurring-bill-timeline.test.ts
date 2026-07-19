import type { RecurringBillOccurrence, RecurringBillTimeline } from '@/api/contracts';
import {
  groupTimeline,
  initialTimelineRange,
  insertTimelinePage,
  nextTimelineRange,
  previousTimelineRange,
  timelineBounds,
  timelineContainsDate,
} from '@/features/recurring-bills/timeline';

function occurrence(
  definitionId: string,
  name: string,
  occurrenceDate: string,
): RecurringBillOccurrence {
  return {
    accountName: null,
    definitionId,
    definitionVersion: 0,
    importCount: 0,
    imports: [],
    name,
    notes: null,
    occurrenceDate,
    payee: null,
    paymentMethod: 'AUTOPAY',
    typicalAmountMinor: 1000,
  };
}

describe('recurring Bill timeline date ranges', () => {
  it('uses the complete current month in the ordinary middle of a month', () => {
    expect(initialTimelineRange('2026-07-14')).toEqual({
      from: '2026-07-01',
      through: '2026-07-31',
    });
  });

  it('bounds the timeline to two calendar months around the current month', () => {
    expect(timelineBounds('2026-07-19')).toEqual({
      from: '2026-05-01',
      through: '2026-09-30',
    });
  });

  it.each([
    ['2026-07-01', '2026-06-24'],
    ['2026-07-07', '2026-06-24'],
  ])('includes the previous month final seven days on %s', (today, from) => {
    expect(initialTimelineRange(today)).toEqual({ from, through: '2026-07-31' });
  });

  it.each([
    ['2026-02-22', '2026-03-07'],
    ['2028-02-23', '2028-03-07'],
    ['2026-04-24', '2026-05-07'],
    ['2026-07-25', '2026-08-07'],
  ])('includes the next month first seven days at the inclusive boundary %s', (today, through) => {
    expect(initialTimelineRange(today)).toEqual({
      from: today.slice(0, 8) + '01',
      through,
    });
  });

  it.each(['2026-02-21', '2028-02-22', '2026-04-23', '2026-07-24'])(
    'does not extend through the next month before the final-seven-days boundary %s',
    (today) => {
      expect(initialTimelineRange(today).through.slice(0, 7)).toBe(today.slice(0, 7));
    },
  );

  it('handles both sides of a year boundary', () => {
    expect(initialTimelineRange('2026-01-03')).toEqual({
      from: '2025-12-25',
      through: '2026-01-31',
    });
    expect(initialTimelineRange('2026-12-27')).toEqual({
      from: '2026-12-01',
      through: '2027-01-07',
    });
  });

  it('extends backward without overlapping the initial preview, then by full months', () => {
    const first = previousTimelineRange('2026-06-24', '2026-07-03');
    const second = previousTimelineRange(first!.from, '2026-07-03');

    expect(first).toEqual({ from: '2026-06-01', through: '2026-06-23' });
    expect(second).toEqual({ from: '2026-05-01', through: '2026-05-31' });
  });

  it('extends forward without overlapping the initial preview, then by full months', () => {
    const first = nextTimelineRange('2026-08-07', '2026-07-25');
    const second = nextTimelineRange(first!.through, '2026-07-25');

    expect(first).toEqual({ from: '2026-08-08', through: '2026-08-31' });
    expect(second).toEqual({ from: '2026-09-01', through: '2026-09-30' });
  });

  it('stops previous loading at current month minus two', () => {
    expect(previousTimelineRange('2026-05-01', '2026-07-19')).toBeUndefined();
    expect(previousTimelineRange('2026-05-08', '2026-07-19')).toEqual({
      from: '2026-05-01',
      through: '2026-05-07',
    });
  });

  it('stops next loading at current month plus two', () => {
    expect(nextTimelineRange('2026-09-30', '2026-07-19')).toBeUndefined();
    expect(nextTimelineRange('2026-09-23', '2026-07-19')).toEqual({
      from: '2026-09-24',
      through: '2026-09-30',
    });
  });

  it('keeps boundary months correct across year transitions', () => {
    expect(timelineBounds('2026-01-15')).toEqual({
      from: '2025-11-01',
      through: '2026-03-31',
    });
    expect(timelineBounds('2026-12-15')).toEqual({
      from: '2026-10-01',
      through: '2027-02-28',
    });
  });
});

describe('recurring Bill timeline grouping', () => {
  it('groups same-date Bills, sorts names case-insensitively, and inserts an empty Today', () => {
    const groups = groupTimeline(
      [
        occurrence('22222222-2222-4222-8222-222222222222', 'YouTube', '2026-07-21'),
        occurrence('11111111-1111-4111-8111-111111111111', 'netflix', '2026-07-21'),
        occurrence('33333333-3333-4333-8333-333333333333', 'Rent', '2026-07-11'),
      ],
      '2026-07-14',
    );

    expect(groups.map((group) => group.date)).toEqual(['2026-07-11', '2026-07-14', '2026-07-21']);
    expect(groups[1].items).toEqual([]);
    expect(groups[2].items.map((item) => item.name)).toEqual(['netflix', 'YouTube']);
  });

  it('deduplicates occurrences and emits Today exactly once across pages', () => {
    const duplicate = occurrence('11111111-1111-4111-8111-111111111111', 'Electric', '2026-07-18');
    const groups = groupTimeline([duplicate, duplicate], '2026-07-18');

    expect(groups).toHaveLength(1);
    expect(groups[0].items).toHaveLength(1);
  });
});

describe('recurring Bill timeline cached pages', () => {
  it('detects whether a date belongs to any loaded range', () => {
    const pages = [page('2026-06-01', '2026-06-30'), page('2026-08-01', '2026-08-31')];

    expect(timelineContainsDate(pages, '2026-06-15')).toBe(true);
    expect(timelineContainsDate(pages, '2026-07-15')).toBe(false);
  });

  it('restores a missing current page in date order without duplicating an existing range', () => {
    const initial = {
      pageParams: [
        { from: '2026-06-01', through: '2026-06-30' },
        { from: '2026-08-01', through: '2026-08-31' },
      ],
      pages: [page('2026-06-01', '2026-06-30'), page('2026-08-01', '2026-08-31')],
    };
    const july = page('2026-07-01', '2026-07-31');
    const restored = insertTimelinePage(initial, july);
    const repeated = insertTimelinePage(restored, july);

    expect(restored.pages.map((item) => item.from)).toEqual([
      '2026-06-01',
      '2026-07-01',
      '2026-08-01',
    ]);
    expect(repeated).toEqual(restored);
  });
});

function page(from: string, through: string): RecurringBillTimeline {
  return { from, items: [], through };
}
