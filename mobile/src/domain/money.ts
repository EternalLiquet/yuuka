type ParseMoneyOptions = {
  allowNegative?: boolean;
};

const MONEY_PATTERN = /^(-)?(?:\$)?((?:\d{1,3}(?:,\d{3})+)|\d+)(?:\.(\d{1,2}))?$/;

export function parseMoneyToMinor(rawValue: string, options: ParseMoneyOptions = {}): number {
  const value = rawValue.trim();
  const match = MONEY_PATTERN.exec(value);
  if (!match) {
    throw new Error('Enter a valid money amount with no more than two decimal places.');
  }

  const negative = Boolean(match[1]);
  if (negative && !options.allowNegative) {
    throw new Error('This money amount cannot be negative.');
  }

  const whole = BigInt(match[2].replaceAll(',', ''));
  const centsText = (match[3] ?? '').padEnd(2, '0');
  const cents = BigInt(centsText || '0');
  const minor = (whole * 100n + cents) * (negative ? -1n : 1n);
  if (minor > BigInt(Number.MAX_SAFE_INTEGER) || minor < BigInt(Number.MIN_SAFE_INTEGER)) {
    throw new Error('This money amount is too large.');
  }
  return Number(minor);
}

export function minorToInput(minor: number): string {
  assertMinor(minor);
  const negative = minor < 0 ? '-' : '';
  const absolute = Math.abs(minor);
  return `${negative}${Math.floor(absolute / 100)}.${String(absolute % 100).padStart(2, '0')}`;
}

export function formatMoney(minor: number, currencyCode = 'USD', locale?: string): string {
  assertMinor(minor);
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: currencyCode,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(minor / 100);
}

function assertMinor(minor: number) {
  if (!Number.isSafeInteger(minor)) {
    throw new Error('Money must be an integer number of minor units.');
  }
}
