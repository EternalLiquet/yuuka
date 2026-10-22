import { ApiError } from '@/api/api-error';
import { AuthStorageError } from '@/auth/auth-storage-error';
import {
  AuthRecoverableError,
  classifyRefreshFailure,
  LocalRefreshExpiredError,
} from '@/auth/refresh-failure';
import { authSessionSchema } from '@/auth/session';

function apiError(status: number) {
  return new ApiError({ code: 'X', message: 'Rejected', status });
}

describe('classifyRefreshFailure', () => {
  it('treats only a 401 or local expiry as an invalid saved sign-in', () => {
    expect(classifyRefreshFailure(apiError(401))).toBe('invalid');
    expect(classifyRefreshFailure(new LocalRefreshExpiredError())).toBe('invalid');
  });

  it.each([403, 429, 500, 503])(
    'treats HTTP %s as a server response, not bad credentials',
    (status) => {
      expect(classifyRefreshFailure(apiError(status))).toBe('server');
    },
  );

  it('treats a malformed successful response as a server problem', () => {
    const result = authSessionSchema.safeParse({});
    expect(result.success).toBe(false);
    if (!result.success) expect(classifyRefreshFailure(result.error)).toBe('server');
  });

  it('classifies secure-storage errors separately', () => {
    expect(classifyRefreshFailure(new AuthStorageError('read'))).toBe('storage');
  });

  it.each([
    ['fetch TypeError', new TypeError('Network request failed')],
    ['abort', Object.assign(new Error('Aborted'), { name: 'AbortError' })],
    ['timeout', Object.assign(new Error('Timed out'), { name: 'TimeoutError' })],
    ['network', Object.assign(new Error('Offline'), { name: 'NetworkError' })],
  ])('classifies %s as a connectivity failure', (_label, error) => {
    expect(classifyRefreshFailure(error)).toBe('network');
  });

  it('does not claim connectivity for unknown failures', () => {
    expect(classifyRefreshFailure(new Error('boom'))).toBe('server');
    expect(classifyRefreshFailure('boom')).toBe('server');
    expect(classifyRefreshFailure(null)).toBe('server');
  });
});

describe('AuthRecoverableError', () => {
  it('mentions connectivity only for network failures and never exposes internals', () => {
    expect(new AuthRecoverableError('network').message).toMatch(/Cannot connect to Yuuka/);
    for (const reason of ['server', 'storage'] as const) {
      const error = new AuthRecoverableError(reason);
      expect(error.reason).toBe(reason);
      expect(error.message).not.toMatch(/Tailscale|connect/i);
      expect(error.message).toMatch(/has not been removed/);
    }
  });
});
