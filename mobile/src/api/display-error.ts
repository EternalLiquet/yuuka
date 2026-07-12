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
  const amountMinor = error.details.amountMinor;
  if (typeof amountMinor !== 'number' || !Number.isSafeInteger(amountMinor)) return null;
  if (error.code === 'PAYCHECK_OVER_ALLOCATED') {
    return `This would over-allocate the paycheck by ${formatMoney(amountMinor, currencyCode)}.`;
  }
  if (error.code === 'PAYBACK_REPAYMENT_OVERPAID') {
    return `This repayment is ${formatMoney(amountMinor, currencyCode)} more than the amount left on this Payback.`;
  }
  if (error.code === 'PAYBACK_BASELINE_BELOW_REPAYMENTS') {
    return `Amount currently left must cover the ${formatMoney(amountMinor, currencyCode)} already repaid in Yuuka.`;
  }
  return null;
}

function safeMessage(message: string, fallback: string) {
  return INTERNAL_TERMS.test(message) ? fallback : message;
}
