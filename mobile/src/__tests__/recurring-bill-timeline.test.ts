import type { RecurringBillOccurrence } from '@/api/contracts';
import { groupTimeline } from '@/features/recurring-bills/timeline';

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
});
