import { formatMoney } from '@/domain/money';

import { ApiError } from './api-error';

const INTERNAL_TERMS =
  /\b(?:minor units?|amountMinor|spentMinor|remainingMinor|unallocatedMinor)\b/i;

export function displayError(
  error: unknown,
  currencyCode = 'USD',
  fallback = 'The request could not be completed.',
) {
  if (error instanceof ApiError) {
    const formatted = moneyError(error, currencyCode);
    if (formatted) return formatted;
    return safeMessage(error.message, fallback);
  }
  if (error instanceof Error) {
    return safeMessage(error.message, fallback);
  }
  return fallback;
}

function moneyError(error: ApiError, currencyCode: string) {
  if (error.code !== 'PAYCHECK_OVER_ALLOCATED') return null;
  const amountMinor = error.details.amountMinor;
  if (typeof amountMinor !== 'number' || !Number.isSafeInteger(amountMinor)) return null;
  return `This would over-allocate the paycheck by ${formatMoney(amountMinor, currencyCode)}.`;
}

function safeMessage(message: string, fallback: string) {
  return INTERNAL_TERMS.test(message) ? fallback : message;
}
