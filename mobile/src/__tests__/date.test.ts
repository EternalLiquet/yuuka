import { yuukaDateInTimezone } from '@/domain/date';

describe('Yuuka date helpers', () => {
  it('derives YYYY-MM-DD in the configured timezone', () => {
    const instant = new Date('2026-07-14T04:30:00.000Z');

    expect(yuukaDateInTimezone('America/Los_Angeles', instant)).toBe('2026-07-13');
    expect(yuukaDateInTimezone('Asia/Tokyo', instant)).toBe('2026-07-14');
  });

  it('falls back to the UTC date when the timezone is invalid', () => {
    expect(yuukaDateInTimezone('Nope/Nowhere', new Date('2026-07-14T04:30:00.000Z'))).toBe(
      '2026-07-14',
    );
  });
});
