import { entryFormSchema, paycheckFormSchema, today } from '@/features/paychecks/form-schemas';

const baseEntry = {
  accountName: '',
  amount: '100.00',
  dueDate: '',
  entryType: 'BILL' as const,
  name: 'Electricity',
  notes: '',
  paybackId: '',
  payee: '',
  target: '',
  targetDate: '',
};

describe('paycheck form schemas', () => {
  it('accepts exact-cent paycheck input', () => {
    expect(
      paycheckFormSchema.safeParse({
        amount: '1,939.23',
        incomeDate: '2026-07-17',
        name: 'UTILITIES 1/2',
        notes: '',
        source: '',
      }).success,
    ).toBe(true);
  });

  it('rejects malformed dates and fractional cents', () => {
    expect(
      paycheckFormSchema.safeParse({
        amount: '1.001',
        incomeDate: '07/17/2026',
        name: '',
        notes: '',
        source: '',
      }).success,
    ).toBe(false);
  });

  it('validates type-specific bill and sinking-fund fields', () => {
    expect(entryFormSchema.safeParse({ ...baseEntry, dueDate: '07/20/2026' }).success).toBe(false);
    expect(
      entryFormSchema.safeParse({
        ...baseEntry,
        entryType: 'SINKING_FUND',
        target: 'bad',
        targetDate: '2026/12/31',
      }).success,
    ).toBe(false);
    expect(
      entryFormSchema.safeParse({
        ...baseEntry,
        entryType: 'SINKING_FUND',
        target: '2500.00',
        targetDate: '2026-12-31',
      }).success,
    ).toBe(true);
  });

  it('returns a local ISO date', () => {
    expect(today()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
