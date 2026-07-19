import { ApiError, mapApiError } from '@/api/api-error';
import { displayError } from '@/api/display-error';

describe('API error mapping', () => {
  it('maps the server error envelope without leaking arbitrary response text', () => {
    const error = mapApiError(409, {
      code: 'STALE_VERSION',
      message: 'This record changed since it was loaded. Refresh and try again.',
      fieldErrors: {},
      traceId: 'trace-1',
    });

    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('STALE_VERSION');
    expect(error.traceId).toBe('trace-1');
  });

  it('uses a safe fallback for malformed responses', () => {
    const error = mapApiError(500, '<html>proxy error</html>');

    expect(error.message).toBe('Yuuka could not complete the request.');
    expect(error.code).toBe('HTTP_500');
  });

  it.each([
    [98, '$0.98'],
    [150, '$1.50'],
    [123456789, '$1,234,567.89'],
  ])('formats over-allocation detail amount %d as %s', (amountMinor, expected) => {
    const error = mapApiError(422, {
      code: 'PAYCHECK_OVER_ALLOCATED',
      message: 'This would over-allocate the paycheck.',
      details: { amountMinor, currencyCode: 'USD' },
      fieldErrors: {},
      traceId: 'trace-1',
    });

    const message = displayError(error, 'USD');

    expect(message).toBe(`This would over-allocate the paycheck by ${expected}.`);
    expect(message).not.toMatch(/minor unit|amountMinor/i);
  });

  it('keeps generic non-money errors unchanged', () => {
    const error = mapApiError(409, {
      code: 'STALE_VERSION',
      message: 'This record changed since it was loaded. Refresh and try again.',
      details: {},
      fieldErrors: {},
      traceId: 'trace-1',
    });

    expect(displayError(error, 'USD')).toBe(
      'This record changed since it was loaded. Refresh and try again.',
    );
  });

  it('uses Expense List terminology for server-supplied domain errors', () => {
    const error = mapApiError(422, {
      code: 'BUSINESS_RULE_VIOLATION',
      message: 'Finalize this Expense Ledger before settling it.',
      details: {},
      fieldErrors: {},
      traceId: 'trace-1',
    });

    expect(displayError(error, 'USD')).toBe('Finalize this Expense List before settling it.');
  });

  it('does not show stale internal money terminology if details are missing', () => {
    const error = mapApiError(422, {
      code: 'BUSINESS_RULE_VIOLATION',
      message: 'This change would over-allocate the paycheck by 98 minor units.',
      fieldErrors: {},
      traceId: 'trace-1',
    });

    const message = displayError(error, 'USD');

    expect(message).toBe('The request could not be completed.');
    expect(message).not.toMatch(/minor unit|amountMinor/i);
  });

  it('describes Payback baseline shortfall as the amount that must be added', () => {
    const error = mapApiError(422, {
      code: 'PAYBACK_BASELINE_BELOW_REPAYMENTS',
      message: 'Balance when tracking began cannot be lower than recorded repayments.',
      details: { amountMinor: 2500, currencyCode: 'USD' },
      fieldErrors: {},
      traceId: 'trace-1',
    });

    expect(displayError(error, 'USD')).toBe(
      'Increase the balance when tracking began by at least $25.00 to cover recorded repayments.',
    );
  });
});
