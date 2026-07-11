import { ApiError, mapApiError } from '@/api/api-error';

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
});
