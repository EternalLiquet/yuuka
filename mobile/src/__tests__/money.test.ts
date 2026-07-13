import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';

describe('money utilities', () => {
  it('parses exact cents without floating point arithmetic', () => {
    expect(parseMoneyToMinor('$1,939.23')).toBe(193923);
    expect(parseMoneyToMinor('0.03')).toBe(3);
    expect(parseMoneyToMinor('12.5')).toBe(1250);
  });

  it('supports signed bucket corrections only when requested', () => {
    expect(parseMoneyToMinor('-5.00', { allowNegative: true })).toBe(-500);
    expect(() => parseMoneyToMinor('-5.00')).toThrow('negative');
  });

  it('rejects fractional cents and malformed values', () => {
    expect(() => parseMoneyToMinor('')).toThrow('valid money');
    expect(() => parseMoneyToMinor('1.001')).toThrow('valid money');
    expect(() => parseMoneyToMinor('one dollar')).toThrow('valid money');
    expect(() => parseMoneyToMinor('9007199254740991.00')).toThrow('too large');
  });

  it('formats integer cents for display', () => {
    expect(formatMoney(193923, 'USD', 'en-US')).toBe('$1,939.23');
    expect(formatMoney(-500, 'USD', 'en-US')).toBe('-$5.00');
  });

  it('rejects non-integer money values for display and editing', () => {
    expect(() => formatMoney(10.5, 'USD', 'en-US')).toThrow('integer');
    expect(() => minorToInput(10.5)).toThrow('integer');
  });

  it('converts stored cents back to editable decimal input', () => {
    expect(minorToInput(3)).toBe('0.03');
    expect(minorToInput(-500)).toBe('-5.00');
  });
});
