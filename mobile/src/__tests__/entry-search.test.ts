import { buildEntrySearchCriteria } from '@/features/search/entry-search';

describe('entry search criteria', () => {
  it('builds name mode queries', () => {
    expect(buildEntrySearchCriteria(' Netflix ', 'NAME')).toEqual({ query: 'Netflix' });
  });

  it('builds exact amount mode criteria with BigInt-safe money parsing', () => {
    expect(buildEntrySearchCriteria('$13.99', 'AMOUNT')).toEqual({ amountMinor: 1399 });
    expect(buildEntrySearchCriteria('13.99', 'ALL')).toEqual({ amountMinor: 1399 });
  });

  it('returns validation instead of a bad amount request', () => {
    expect(buildEntrySearchCriteria('$13.999', 'AMOUNT')).toMatchObject({
      error: expect.stringContaining('valid money'),
    });
  });

  it('does not produce criteria for empty input', () => {
    expect(buildEntrySearchCriteria('   ', 'ALL')).toBeNull();
  });
});
