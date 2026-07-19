import { formatMoney } from '@/domain/money';

import { ApiError } from './api-error';

const INTERNAL_TERMS =
  /\b(?:minor units?|amountMinor|spentMinor|remainingMinor|unallocatedMinor)\b/i;

const ERROR_CODE_MESSAGES: Readonly<Record<string, string>> = {
  EXPENSE_LEDGER_EMPTY: 'Add at least one positive expense before finalizing this expense list.',
};

export function displayError(
  error: unknown,
  currencyCode = 'USD',
  fallback = 'The request could not be completed.',
) {
  if (error instanceof ApiError) {
    const codeMessage = ERROR_CODE_MESSAGES[error.code];
    if (codeMessage) return codeMessage;
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
    return `Increase the balance when tracking began by at least ${formatMoney(amountMinor, currencyCode)} to cover recorded repayments.`;
  }
  return null;
}

function safeMessage(message: string, fallback: string) {
  if (INTERNAL_TERMS.test(message)) return fallback;
  return message
    .replace(/\bExpense Ledgers\b/g, 'Expense Lists')
    .replace(/\bExpense Ledger\b/g, 'Expense List')
    .replace(/\bSinking Funds\b/g, 'Planned Savings')
    .replace(/\bSinking Fund\b/g, 'Planned Savings');
}
