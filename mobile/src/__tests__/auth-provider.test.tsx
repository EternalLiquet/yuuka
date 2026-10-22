import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, waitFor } from '@testing-library/react-native';
import { useEffect } from 'react';
import type { PropsWithChildren } from 'react';
import { AppState, Text } from 'react-native';
import { ZodError } from 'zod';

import { ApiError } from '@/api/api-error';
import { AuthProvider, useAuth } from '@/auth/auth-provider';
import { AuthStorageError } from '@/auth/auth-storage-error';
import { AuthRecoverableError } from '@/auth/refresh-failure';
import { authSessionSchema } from '@/auth/session';
import type { AuthSession } from '@/auth/session';

const operations: string[] = [];
const mockClearSession = jest.fn();
const mockGetStoredSession = jest.fn();
const mockStoreSession = jest.fn();
const mockLogin = jest.fn();
const mockLogout = jest.fn();
const mockRefreshSession = jest.fn();
const mockApiRequest = jest.fn();
const SERVER_A = 'http://localhost:8080/api/v1';
const SERVER_B = 'https://replacement.test/api/v1';
let mockApiBaseUrl = SERVER_A;

jest.mock('@/auth/auth-storage', () => ({
  clearSession: () => {
    operations.push('clear');
    return mockClearSession();
  },
  getStoredSession: () => {
    operations.push('get');
    return mockGetStoredSession();
  },
  storeSession: (session: AuthSession) => {
    operations.push(`store:${session.accessToken}`);
    return mockStoreSession(session);
  },
}));

jest.mock('@/auth/auth-api', () => ({
  login: (...args: unknown[]) => mockLogin(...args),
  logout: (...args: unknown[]) => mockLogout(...args),
  refreshSession: (...args: unknown[]) => mockRefreshSession(...args),
}));

jest.mock('@/api/api-client', () => ({
  apiRequest: (...args: unknown[]) => mockApiRequest(...args),
}));

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: {
      apiBaseUrl: mockApiBaseUrl,
      currencyCode: 'USD',
      theme: 'dark',
      timezone: 'America/Indianapolis',
    },
  }),
}));

type Auth = ReturnType<typeof useAuth>;
let auth: Auth;
let appStateListener: ((state: string) => void) | null = null;
const realNow = Date.now.bind(Date);

const signInValues = { email: 'owner@example.com', password: 'password', totpCode: '123456' };

describe('AuthProvider session behavior', () => {
  beforeEach(() => {
    jest.restoreAllMocks();
    jest.clearAllMocks();
    operations.length = 0;
    mockApiBaseUrl = SERVER_A;
    appStateListener = null;
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      appStateListener = listener as (state: string) => void;
      return { remove: jest.fn() };
    });
    mockLogout.mockResolvedValue(undefined);
    mockClearSession.mockResolvedValue(undefined);
    mockStoreSession.mockResolvedValue(undefined);
    mockApiRequest.mockResolvedValue({ status: 200 });
  });

  it('clears private query data when signing out', async () => {
    const queryClient = newQueryClient();
    queryClient.setQueryData(['paychecks', 'active'], { items: ['private'] });
    mockGetStoredSession.mockResolvedValue(freshSession());

    const view = await renderWithAuth(queryClient);

    await view.findByText('status:authenticated');
    await act(() => auth.signOut());

    expect(view.getByText('status:signedOut')).toBeTruthy();
    expect(mockLogout).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1',
      freshSession().refreshToken,
    );
    expect(operations).toEqual(['get', 'clear']);
    expect(queryClient.getQueryData(['paychecks', 'active'])).toBeUndefined();
  });

  describe('server changes', () => {
    it('revokes on the issuing server when storage deletion spans a server change', async () => {
      mockGetStoredSession.mockResolvedValue(freshSession());
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:authenticated');
      const clear = deferred<void>();
      mockClearSession.mockReturnValueOnce(clear.promise);
      let signedOut: Promise<void> = Promise.resolve();
      await act(async () => {
        signedOut = auth.signOut();
      });
      mockApiBaseUrl = SERVER_B;
      await view.rerender(<AuthProbe />);
      await act(async () => {
        clear.resolve();
        await signedOut;
      });
      expect(mockLogout).toHaveBeenCalledWith(SERVER_A, freshSession().refreshToken);
      expect(mockLogout.mock.calls.every(([url]) => url === SERVER_A)).toBe(true);
    });

    it('discards a late login and revokes it on its original server', async () => {
      mockGetStoredSession.mockResolvedValue(null);
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:signedOut');
      const login = deferred<AuthSession>();
      mockLogin.mockReturnValueOnce(login.promise);
      let signedIn: Promise<void> = Promise.resolve();
      await act(async () => {
        signedIn = auth.signIn(signInValues);
      });
      mockApiBaseUrl = SERVER_B;
      await view.rerender(<AuthProbe />);
      await act(async () => {
        login.resolve(rotatedSession('old-login'));
        await signedIn;
      });
      expect(view.getByText('session:none')).toBeTruthy();
      expect(mockStoreSession).not.toHaveBeenCalled();
      expect(mockLogout).toHaveBeenCalledWith(SERVER_A, rotatedSession('old-login').refreshToken);
    });

    it('discards a late refresh and revokes it on its original server', async () => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      const refresh = deferred<AuthSession>();
      mockRefreshSession.mockReturnValueOnce(refresh.promise);
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(mockRefreshSession).toHaveBeenCalled());
      mockApiBaseUrl = SERVER_B;
      await view.rerender(<AuthProbe />);
      await act(async () => {
        refresh.resolve(rotatedSession('old-refresh'));
      });
      expect(view.getByText('session:none')).toBeTruthy();
      expect(mockStoreSession).not.toHaveBeenCalled();
      expect(mockLogout).toHaveBeenCalledWith(SERVER_A, rotatedSession('old-refresh').refreshToken);
    });

    it('revokes a rotated token on its issuer when a late storage write rejects after a server change', async () => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockResolvedValueOnce(rotatedSession('unsaved-rotation'));
      const write = deferred<void>();
      mockStoreSession.mockReturnValueOnce(write.promise);
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(mockStoreSession).toHaveBeenCalled());
      mockApiBaseUrl = SERVER_B;
      await view.rerender(<AuthProbe />);
      await act(async () => {
        write.reject(new AuthStorageError('write'));
      });
      await view.findByText('status:signedOut');
      expect(mockLogout).toHaveBeenCalledWith(
        SERVER_A,
        rotatedSession('unsaved-rotation').refreshToken,
      );
      expect(mockLogout.mock.calls.every(([url]) => url === SERVER_A)).toBe(true);
      expect(view.getByText('session:none')).toBeTruthy();
      expect(operations).toEqual(['get', 'store:unsaved-rotation', 'clear']);
    });

    it('clears a late login write and revokes on its original server', async () => {
      mockGetStoredSession.mockResolvedValue(null);
      mockLogin.mockResolvedValueOnce(rotatedSession('old-write'));
      const write = deferred<void>();
      mockStoreSession.mockReturnValueOnce(write.promise);
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:signedOut');
      let signedIn: Promise<void> = Promise.resolve();
      await act(async () => {
        signedIn = auth.signIn(signInValues);
      });
      await waitFor(() => expect(mockStoreSession).toHaveBeenCalled());
      mockApiBaseUrl = SERVER_B;
      await view.rerender(<AuthProbe />);
      await act(async () => {
        write.resolve();
        await signedIn;
      });
      await view.findByText('status:signedOut');
      expect(view.getByText('session:none')).toBeTruthy();
      expect(operations).toEqual(['get', 'store:old-write', 'clear']);
      expect(mockLogout).toHaveBeenCalledWith(SERVER_A, rotatedSession('old-write').refreshToken);
    });

    it('never restores stored credentials issued by a different server', async () => {
      mockApiBaseUrl = SERVER_B;
      mockGetStoredSession.mockResolvedValue({ ...expiredAccessSession(), apiBaseUrl: SERVER_A });
      mockRefreshSession.mockResolvedValue(rotatedSession('wrong-server'));
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(auth.isLoading).toBe(false));
      expect(view.getByText('session:none')).toBeTruthy();
      expect(mockRefreshSession).not.toHaveBeenCalled();
      expect(mockApiRequest).not.toHaveBeenCalled();
      expect(mockClearSession).toHaveBeenCalledTimes(1);
    });

    it('persists the issuing server with a new login', async () => {
      mockGetStoredSession.mockResolvedValue(null);
      mockLogin.mockResolvedValueOnce(rotatedSession('new-login'));
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:signedOut');
      await act(() => auth.signIn(signInValues));
      expect(mockStoreSession).toHaveBeenCalledWith({
        ...rotatedSession('new-login'),
        apiBaseUrl: SERVER_A,
      });
    });
  });

  describe('startup refresh that cannot reach Yuuka', () => {
    it('retains saved credentials, shows neither protected nor signed-out state', async () => {
      const queryClient = newQueryClient();
      queryClient.setQueryData(['paycheck', 'private-id'], { name: 'Private' });
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValue(new TypeError('Network request failed'));

      const view = await renderWithAuth(queryClient);

      await view.findByText('status:recovering');
      expect(view.getByText('reason:network')).toBeTruthy();
      expect(view.getByText('pending:false')).toBeTruthy();
      expect(view.getByText('session:none')).toBeTruthy();
      expect(view.getByText('expired:false')).toBeTruthy();
      expect(auth.isLoading).toBe(false);
      expect(mockClearSession).not.toHaveBeenCalled();
      expect(operations).toEqual(['get']);
      expect(queryClient.getQueryData(['paycheck', 'private-id'])).toBeDefined();
      await expect(auth.authenticatedRequest('/me')).rejects.toThrow('Authentication is required');
      expect(mockApiRequest).not.toHaveBeenCalled();
    });

    it.each([
      ['timeout', Object.assign(new Error('Aborted'), { name: 'AbortError' })],
      ['DNS or connectivity', new TypeError('Network request failed')],
      ['TLS', new TypeError('SSL handshake failed')],
    ])('treats %s as recoverable without relaxing anything', async (_label, error) => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValue(error);

      const view = await renderWithAuth(newQueryClient());

      await view.findByText('reason:network');
      expect(mockClearSession).not.toHaveBeenCalled();
      expect(view.getByText('expired:false')).toBeTruthy();
    });

    it.each([
      ['403', apiError(403, 'FORBIDDEN')],
      ['429', apiError(429, 'RATE_LIMITED')],
      ['500', apiError(500, 'INTERNAL')],
      ['malformed 2xx', zodError()],
      ['unknown failure', new Error('boom')],
    ])('treats %s as recoverable with generic reason', async (_label, error) => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValue(error);

      const view = await renderWithAuth(newQueryClient());

      await view.findByText('reason:server');
      expect(mockClearSession).not.toHaveBeenCalled();
      expect(view.getByText('expired:false')).toBeTruthy();
    });

    it('treats a secure-storage write failure as recoverable and keeps the rotated token', async () => {
      const rotated = rotatedSession('rotated');
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockResolvedValueOnce(rotated);
      mockStoreSession.mockRejectedValueOnce(new AuthStorageError('write'));

      const view = await renderWithAuth(newQueryClient());

      await view.findByText('reason:storage');
      expect(mockClearSession).not.toHaveBeenCalled();
      expect(view.getByText('session:none')).toBeTruthy();

      const again = rotatedSession('again');
      mockRefreshSession.mockResolvedValueOnce(again);
      await act(() => auth.retryRecovery());

      expect(mockRefreshSession).toHaveBeenLastCalledWith(
        'http://localhost:8080/api/v1',
        rotated.refreshToken,
      );
      expect(view.getByText('status:authenticated')).toBeTruthy();
      expect(operations).toEqual(['get', 'store:rotated', 'store:again']);
    });

    it('treats a secure-storage read failure as recoverable and re-reads on retry', async () => {
      mockGetStoredSession.mockRejectedValueOnce(new AuthStorageError('read'));

      const view = await renderWithAuth(newQueryClient());

      await view.findByText('reason:storage');
      expect(mockClearSession).not.toHaveBeenCalled();
      expect(mockRefreshSession).not.toHaveBeenCalled();

      mockGetStoredSession.mockResolvedValueOnce(freshSession());
      await act(() => auth.retryRecovery());

      expect(view.getByText('status:authenticated')).toBeTruthy();
      expect(operations).toEqual(['get', 'get']);
    });

    it('persists the rotated session and proceeds after repeated failed retries', async () => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValue(new TypeError('Network request failed'));
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:recovering');

      await act(() => auth.retryRecovery());
      await act(() => auth.retryRecovery());
      expect(view.getByText('status:recovering')).toBeTruthy();
      expect(view.getByText('pending:false')).toBeTruthy();
      expect(mockRefreshSession).toHaveBeenCalledTimes(3);
      expect(operations).toEqual(['get']);

      mockRefreshSession.mockResolvedValueOnce(rotatedSession('recovered'));
      await act(() => auth.retryRecovery());

      expect(view.getByText('status:authenticated')).toBeTruthy();
      expect(view.getByText('session:recovered')).toBeTruthy();
      expect(mockStoreSession).toHaveBeenCalledWith({
        ...rotatedSession('recovered'),
        apiBaseUrl: SERVER_A,
      });
      expect(operations).toEqual(['get', 'store:recovered']);
      expect(mockLogin).not.toHaveBeenCalled();
      expect(mockRefreshSession).toHaveBeenLastCalledWith(
        'http://localhost:8080/api/v1',
        expiredAccessSession().refreshToken,
      );
    });

    it('shares one refresh across retry taps, foreground events, and shows pending', async () => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValueOnce(new TypeError('Network request failed'));
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:recovering');
      const refresh = deferred<AuthSession>();
      mockRefreshSession.mockReturnValueOnce(refresh.promise);

      let first: Promise<void> = Promise.resolve();
      await act(async () => {
        first = auth.retryRecovery();
        void auth.retryRecovery();
        void auth.retryRecovery();
        appStateListener?.('active');
      });

      expect(mockRefreshSession).toHaveBeenCalledTimes(2);
      expect(view.getByText('pending:true')).toBeTruthy();

      await act(async () => {
        refresh.reject(new TypeError('Network request failed'));
        await first;
      });

      expect(view.getByText('pending:false')).toBeTruthy();
      expect(view.getByText('status:recovering')).toBeTruthy();
      mockRefreshSession.mockRejectedValueOnce(new TypeError('Network request failed'));
      await act(async () => {
        appStateListener?.('active');
        appStateListener?.('background');
      });
      await waitFor(() => expect(mockRefreshSession).toHaveBeenCalledTimes(3));
    });

    it('ignores foreground events unless recovering', async () => {
      mockGetStoredSession.mockResolvedValue(freshSession());
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:authenticated');

      await act(async () => {
        appStateListener?.('active');
        await auth.retryRecovery();
      });

      expect(mockRefreshSession).not.toHaveBeenCalled();
    });

    it('rechecks local refresh expiry at retry time without calling the server', async () => {
      const queryClient = newQueryClient();
      queryClient.setQueryData(['paycheck', 'private-id'], { name: 'Private' });
      const launch = realNow();
      const stored = {
        ...expiredAccessSession(),
        refreshExpiresAt: new Date(launch + 120_000).toISOString(),
      };
      mockGetStoredSession.mockResolvedValue(stored);
      mockRefreshSession.mockRejectedValue(new TypeError('Network request failed'));
      const view = await renderWithAuth(queryClient);
      await view.findByText('status:recovering');

      jest.spyOn(Date, 'now').mockReturnValue(launch + 3_600_000);
      await act(() => auth.retryRecovery());

      expect(mockRefreshSession).toHaveBeenCalledTimes(1);
      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(view.getByText('expired:true')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear']);
      expect(queryClient.getQueryData(['paycheck', 'private-id'])).toBeUndefined();
    });
  });

  describe('definitive rejection', () => {
    it('clears credentials and cache when the server rejects the refresh token', async () => {
      const queryClient = newQueryClient();
      queryClient.setQueryData(['paycheck', 'private-id'], { name: 'Private' });
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValue(apiError(401, 'INVALID_REFRESH_TOKEN'));

      const view = await renderWithAuth(queryClient);

      await view.findByText('status:signedOut');
      expect(view.getByText('expired:true')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear']);
      expect(queryClient.getQueryData(['paycheck', 'private-id'])).toBeUndefined();
    });

    it('clears credentials without a server call when the refresh token already expired', async () => {
      mockGetStoredSession.mockResolvedValue({
        ...expiredAccessSession(),
        refreshExpiresAt: '2020-01-01T00:00:00.000Z',
      });

      const view = await renderWithAuth(newQueryClient());

      await view.findByText('status:signedOut');
      expect(view.getByText('expired:true')).toBeTruthy();
      expect(mockRefreshSession).not.toHaveBeenCalled();
      expect(operations).toEqual(['get', 'clear']);
    });

    it('goes to sign-in when no session is stored', async () => {
      mockGetStoredSession.mockResolvedValue(null);
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:signedOut');
      expect(view.getByText('expired:false')).toBeTruthy();
    });

    it('keeps failed credential cleanup visible after a rejection', async () => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValue(apiError(401, 'INVALID_REFRESH_TOKEN'));
      mockClearSession.mockRejectedValue(new AuthStorageError('clear'));

      const view = await renderWithAuth(newQueryClient());

      await view.findByText('reason:signOut');
      expect(view.getByText('session:none')).toBeTruthy();
      expect(view.getByText('expired:true')).toBeTruthy();
    });
  });

  describe('already-open session', () => {
    async function openSession(queryClient: QueryClient) {
      mockGetStoredSession.mockResolvedValue(freshSession());
      const view = await renderWithAuth(queryClient);
      await view.findByText('status:authenticated');
      return view;
    }

    it.each([
      ['sign-out', 401],
      ['replacement sign-in', 401],
      ['sign-out', 200],
      ['replacement sign-in', 200],
    ] as const)(
      'discards an original request returning %s / %s after the auth epoch changes',
      async (transition, status) => {
        const client = newQueryClient();
        const view = await openSession(client);
        const original = deferred<Response>();
        mockApiRequest.mockReturnValueOnce(original.promise);
        mockRefreshSession.mockRejectedValue(apiError(401, 'INVALID_REFRESH_TOKEN'));
        let result: Promise<unknown> = Promise.resolve();
        await act(async () => {
          result = auth.authenticatedRequest('/me').catch((error: unknown) => error);
        });
        if (transition === 'sign-out') {
          await act(() => auth.signOut());
        } else {
          mockLogin.mockResolvedValueOnce(rotatedSession('replacement'));
          await act(() => auth.signIn(signInValues));
          client.setQueryData(['new-login'], 'private');
        }
        const before = [...operations];
        let outcome: unknown;
        await act(async () => {
          original.resolve({ status } as Response);
          outcome = await result;
        });
        expect(outcome).toEqual(new Error('Authentication is required'));
        expect(mockRefreshSession).not.toHaveBeenCalled();
        expect(mockApiRequest).toHaveBeenCalledTimes(1);
        expect(operations).toEqual(before);
        expect(view.getByText('expired:false')).toBeTruthy();
        if (transition === 'sign-out') {
          expect(view.getByText('status:signedOut')).toBeTruthy();
        } else {
          expect(view.getByText('session:replacement')).toBeTruthy();
          expect(client.getQueryData(['new-login'])).toBe('private');
        }
      },
    );

    it('keeps session and cache when the refresh cannot connect, then recovers', async () => {
      const queryClient = newQueryClient();
      queryClient.setQueryData(['paycheck', 'private-id'], { name: 'Private' });
      const view = await openSession(queryClient);
      jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2099-07-10T11:59:59.000Z'));
      mockRefreshSession.mockRejectedValueOnce(new TypeError('Network request failed'));

      const failure = await auth.authenticatedRequest('/me').catch((error: unknown) => error);

      expect(failure).toBeInstanceOf(AuthRecoverableError);
      expect((failure as AuthRecoverableError).reason).toBe('network');
      expect(mockApiRequest).not.toHaveBeenCalled();
      expect(view.getByText('status:authenticated')).toBeTruthy();
      expect(view.getByText('session:access-token')).toBeTruthy();
      expect(queryClient.getQueryData(['paycheck', 'private-id'])).toBeDefined();
      expect(mockClearSession).not.toHaveBeenCalled();

      mockRefreshSession.mockResolvedValueOnce(rotatedSession('later'));
      await act(() => auth.authenticatedRequest('/me'));
      expect(mockApiRequest).toHaveBeenCalledTimes(1);
      expect(view.getByText('session:later')).toBeTruthy();
    });

    it('keeps an open session on a server failure and rejects with a generic error', async () => {
      await openSession(newQueryClient());
      jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2099-07-10T11:59:59.000Z'));
      mockRefreshSession.mockRejectedValueOnce(apiError(503, 'UNAVAILABLE'));

      const failure = await auth.authenticatedRequest('/me').catch((error: unknown) => error);

      expect((failure as AuthRecoverableError).reason).toBe('server');
      expect((failure as Error).message).not.toMatch(/Tailscale/i);
      expect(mockClearSession).not.toHaveBeenCalled();
    });

    it('expires an open session when the refresh token is rejected', async () => {
      const queryClient = newQueryClient();
      queryClient.setQueryData(['paycheck', 'private-id'], { name: 'Private' });
      const view = await openSession(queryClient);
      jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2099-07-10T11:59:59.000Z'));
      mockRefreshSession.mockRejectedValueOnce(apiError(401, 'INVALID_REFRESH_TOKEN'));

      await act(async () => {
        await auth.authenticatedRequest('/me').catch(() => undefined);
      });

      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(view.getByText('expired:true')).toBeTruthy();
      expect(queryClient.getQueryData(['paycheck', 'private-id'])).toBeUndefined();
    });

    it('shares one refresh across overlapping requests and never sends the expired token', async () => {
      await openSession(newQueryClient());
      jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2099-07-10T11:59:59.000Z'));
      const refresh = deferred<AuthSession>();
      mockRefreshSession.mockReturnValueOnce(refresh.promise);

      let requests: Promise<unknown> = Promise.resolve();
      await act(async () => {
        requests = Promise.all([auth.authenticatedRequest('/a'), auth.authenticatedRequest('/b')]);
      });
      await act(async () => {
        refresh.resolve(rotatedSession('shared'));
        await requests;
      });

      expect(mockRefreshSession).toHaveBeenCalledTimes(1);
      expect(mockApiRequest).toHaveBeenCalledTimes(2);
      for (const [, init] of mockApiRequest.mock.calls) {
        expect(init.headers.Authorization).toBe('Bearer shared');
      }
    });

    it('refreshes once on a 401 and stops when the retry is still 401', async () => {
      await openSession(newQueryClient());
      mockApiRequest.mockResolvedValue({ status: 401 });
      mockRefreshSession.mockResolvedValue(rotatedSession('next'));

      const response = await act(() => auth.authenticatedRequest('/me'));

      expect(response.status).toBe(401);
      expect(mockRefreshSession).toHaveBeenCalledTimes(1);
      expect(mockApiRequest).toHaveBeenCalledTimes(2);
    });

    it('does not refresh a second time when a just-refreshed token is still rejected', async () => {
      await openSession(newQueryClient());
      jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2099-07-10T11:59:59.000Z'));
      mockApiRequest.mockResolvedValue({ status: 401 });
      mockRefreshSession.mockResolvedValue(rotatedSession('next'));

      const response = await act(() => auth.authenticatedRequest('/me'));

      expect(response.status).toBe(401);
      expect(mockRefreshSession).toHaveBeenCalledTimes(1);
      expect(mockApiRequest).toHaveBeenCalledTimes(1);
    });
  });

  describe('sign out and late results', () => {
    it('signs out offline even when server logout fails', async () => {
      const queryClient = newQueryClient();
      queryClient.setQueryData(['paycheck', 'private-id'], { name: 'Private' });
      mockGetStoredSession.mockResolvedValue(freshSession());
      mockLogout.mockRejectedValue(new TypeError('Network request failed'));
      const view = await renderWithAuth(queryClient);
      await view.findByText('status:authenticated');

      await act(() => auth.signOut());

      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(view.getByText('expired:false')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear']);
      expect(queryClient.getQueryData(['paycheck', 'private-id'])).toBeUndefined();
    });

    it('does not wait for a hanging server logout', async () => {
      mockGetStoredSession.mockResolvedValue(freshSession());
      mockLogout.mockReturnValue(new Promise(() => undefined));
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:authenticated');

      await act(() => auth.signOut());

      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(mockLogout).toHaveBeenCalledTimes(1);
    });

    it('signs out with the rotated token when its store write failed on an open session', async () => {
      mockGetStoredSession.mockResolvedValue(freshSession());
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:authenticated');
      jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2099-07-10T11:59:59.000Z'));
      const rotated = rotatedSession('rotated');
      mockRefreshSession.mockResolvedValueOnce(rotated);
      mockStoreSession.mockRejectedValueOnce(new AuthStorageError('write'));
      await auth.authenticatedRequest('/me').catch(() => undefined);

      await act(() => auth.signOut());

      expect(mockLogout).toHaveBeenCalledWith('http://localhost:8080/api/v1', rotated.refreshToken);
      expect(mockLogout).not.toHaveBeenCalledWith(
        'http://localhost:8080/api/v1',
        freshSession().refreshToken,
      );
      expect(operations).toEqual(['get', 'store:rotated', 'clear']);
      expect(view.getByText('status:signedOut')).toBeTruthy();
    });

    it('signs out of a recovering session using the retained refresh token', async () => {
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockRejectedValue(new TypeError('Network request failed'));
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:recovering');

      await act(() => auth.signOut());

      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(view.getByText('expired:false')).toBeTruthy();
      expect(mockLogout).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1',
        expiredAccessSession().refreshToken,
      );
      expect(operations).toEqual(['get', 'clear']);
    });

    it('locks private data and keeps cleanup retry available when secure storage cannot be cleared', async () => {
      mockGetStoredSession.mockResolvedValue(freshSession());
      const client = newQueryClient();
      client.setQueryData(['private'], 'private');
      const view = await renderWithAuth(client);
      await view.findByText('status:authenticated');
      mockClearSession.mockRejectedValueOnce(new AuthStorageError('clear'));

      await act(async () => {
        await expect(auth.signOut()).rejects.toBeInstanceOf(AuthStorageError);
      });

      expect(view.getByText('status:recovering')).toBeTruthy();
      expect(view.getByText('reason:signOut')).toBeTruthy();
      expect(view.getByText('pending:false')).toBeTruthy();
      expect(view.getByText('session:none')).toBeTruthy();
      expect(client.getQueryData(['private'])).toBeUndefined();
      expect(mockLogout).toHaveBeenCalledTimes(1);
      await expect(auth.authenticatedRequest('/me')).rejects.toThrow('Authentication is required');
      await expect(auth.signIn(signInValues)).rejects.toThrow('Sign-out cleanup is required');
      expect(mockLogin).not.toHaveBeenCalled();

      const clear = deferred<void>();
      mockClearSession.mockReturnValueOnce(clear.promise);
      let retry: Promise<void> = Promise.resolve();
      await act(async () => {
        retry = auth.retryRecovery();
        void auth.retryRecovery();
        appStateListener?.('active');
      });
      expect(view.getByText('pending:true')).toBeTruthy();
      expect(mockClearSession).toHaveBeenCalledTimes(2);
      expect(mockGetStoredSession).toHaveBeenCalledTimes(1);
      expect(mockRefreshSession).not.toHaveBeenCalled();
      await act(async () => {
        clear.resolve();
        await retry;
      });
      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear', 'clear']);
    });

    it('does not resurrect a signed-out session from a late successful refresh', async () => {
      const refresh = deferred<AuthSession>();
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockReturnValue(refresh.promise);
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(mockRefreshSession).toHaveBeenCalled());

      await act(() => auth.signOut());
      await act(async () => {
        refresh.resolve(rotatedSession('late'));
      });

      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(view.getByText('session:none')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear']);
      expect(mockLogout).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1',
        rotatedSession('late').refreshToken,
      );
    });

    it('does not enter recovery or expire from a late failed refresh after sign-out', async () => {
      const refresh = deferred<AuthSession>();
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockReturnValue(refresh.promise);
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(mockRefreshSession).toHaveBeenCalled());

      await act(() => auth.signOut());
      await act(async () => {
        refresh.reject(new TypeError('Network request failed'));
      });

      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(view.getByText('expired:false')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear']);
    });

    it('does not resurrect a session when the stored read resolves after sign-out', async () => {
      const read = deferred<AuthSession | null>();
      mockGetStoredSession.mockReturnValue(read.promise);
      const view = await renderWithAuth(newQueryClient());

      let signedOut: Promise<void> = Promise.resolve();
      await act(async () => {
        signedOut = auth.signOut();
      });
      expect(view.getByText('reason:signOut')).toBeTruthy();
      expect(view.getByText('pending:true')).toBeTruthy();
      await act(async () => {
        read.resolve(freshSession());
        await signedOut;
      });

      expect(view.getByText('status:signedOut')).toBeTruthy();
      expect(view.getByText('session:none')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear']);
    });

    it('keeps a newer sign-in when an older refresh succeeds late', async () => {
      const refresh = deferred<AuthSession>();
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockReturnValue(refresh.promise);
      mockLogin.mockResolvedValue(rotatedSession('new-login'));
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(mockRefreshSession).toHaveBeenCalled());

      await act(() => auth.signIn(signInValues));
      await act(async () => {
        refresh.resolve(rotatedSession('old-late'));
      });

      expect(view.getByText('session:new-login')).toBeTruthy();
      expect(operations).toEqual(['get', 'store:new-login']);
    });

    it('lets a newer sign-in win when the older rotated write is already in progress', async () => {
      const oldWrite = deferred<void>();
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockResolvedValue(rotatedSession('old-late'));
      mockStoreSession.mockImplementation((session: AuthSession) =>
        session.accessToken === 'old-late' ? oldWrite.promise : Promise.resolve(),
      );
      mockLogin.mockResolvedValue(rotatedSession('new-login'));
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(operations).toContain('store:old-late'));

      let signedIn: Promise<void> = Promise.resolve();
      await act(async () => {
        signedIn = auth.signIn(signInValues);
      });
      await act(async () => {
        oldWrite.resolve();
        await signedIn;
      });

      expect(operations).toEqual(['get', 'store:old-late', 'store:new-login']);
      expect(view.getByText('session:new-login')).toBeTruthy();
    });

    it('does not expire a newer sign-in when an older refresh is rejected late', async () => {
      const refresh = deferred<AuthSession>();
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockReturnValue(refresh.promise);
      mockLogin.mockResolvedValue(rotatedSession('new-login'));
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(mockRefreshSession).toHaveBeenCalled());

      await act(() => auth.signIn(signInValues));
      await act(async () => {
        refresh.reject(apiError(401, 'INVALID_REFRESH_TOKEN'));
      });

      expect(view.getByText('session:new-login')).toBeTruthy();
      expect(view.getByText('expired:false')).toBeTruthy();
      expect(operations).toEqual(['get', 'store:new-login']);
    });

    it('leaves storage cleared when sign-out happens during an in-progress rotated write', async () => {
      const oldWrite = deferred<void>();
      mockGetStoredSession.mockResolvedValue(expiredAccessSession());
      mockRefreshSession.mockResolvedValue(rotatedSession('old-late'));
      mockStoreSession.mockReturnValue(oldWrite.promise);
      const view = await renderWithAuth(newQueryClient());
      await waitFor(() => expect(operations).toContain('store:old-late'));

      let signedOut: Promise<void> = Promise.resolve();
      await act(async () => {
        signedOut = auth.signOut();
      });
      await act(async () => {
        oldWrite.resolve();
        await signedOut;
      });

      expect(operations).toEqual(['get', 'store:old-late', 'clear']);
      expect(view.getByText('status:signedOut')).toBeTruthy();
    });

    it('discards a sign-in that finishes after the user signed out', async () => {
      const login = deferred<AuthSession>();
      mockGetStoredSession.mockResolvedValue(null);
      mockLogin.mockReturnValue(login.promise);
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:signedOut');

      let signedIn: Promise<void> = Promise.resolve();
      await act(async () => {
        signedIn = auth.signIn(signInValues);
      });
      await act(() => auth.signOut());
      await act(async () => {
        login.resolve(rotatedSession('too-late'));
        await signedIn;
      });

      expect(view.getByText('session:none')).toBeTruthy();
      expect(operations).toEqual(['get', 'clear']);
      expect(mockLogout).toHaveBeenCalledWith(
        'http://localhost:8080/api/v1',
        rotatedSession('too-late').refreshToken,
      );
    });

    it('signs in and surfaces a storage failure without authenticating', async () => {
      mockGetStoredSession.mockResolvedValue(null);
      mockLogin.mockResolvedValue(rotatedSession('new-login'));
      mockStoreSession.mockRejectedValue(new AuthStorageError('write'));
      const view = await renderWithAuth(newQueryClient());
      await view.findByText('status:signedOut');

      await act(async () => {
        await expect(auth.signIn(signInValues)).rejects.toBeInstanceOf(AuthStorageError);
      });

      expect(view.getByText('session:none')).toBeTruthy();
    });

    it('throws when auth is used outside the provider', async () => {
      const spy = jest.spyOn(console, 'error').mockImplementation(() => undefined);
      function Naked() {
        useAuth();
        return null;
      }
      await expect(render(<Naked />)).rejects.toThrow('useAuth must be used inside AuthProvider');
      spy.mockRestore();
    });
  });
});

function newQueryClient() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
}

async function renderWithAuth(queryClient: QueryClient) {
  function Wrapper({ children }: PropsWithChildren) {
    return (
      <QueryClientProvider client={queryClient}>
        <AuthProvider>{children}</AuthProvider>
      </QueryClientProvider>
    );
  }
  return render(<AuthProbe />, { wrapper: Wrapper });
}

function AuthProbe() {
  const value = useAuth();
  useEffect(() => {
    auth = value;
  });
  const { ...state } = value;
  return <ProbeText auth={state} />;
}

function ProbeText({ auth }: { auth: Auth }) {
  return (
    <>
      <Text>{`status:${auth.status}`}</Text>
      <Text>{`reason:${auth.recovery?.reason ?? 'none'}`}</Text>
      <Text>{`pending:${auth.recovery?.pending ?? false}`}</Text>
      <Text>{`session:${auth.session?.accessToken ?? 'none'}`}</Text>
      <Text>{`expired:${auth.sessionExpired}`}</Text>
    </>
  );
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, reject, resolve };
}

function apiError(status: number, code: string) {
  return new ApiError({ code, message: 'Rejected', status });
}

function zodError() {
  const result = authSessionSchema.safeParse({});
  if (result.success) throw new Error('expected a parse failure');
  return result.error satisfies ZodError;
}

function freshSession(): AuthSession {
  return {
    accessToken: 'access-token',
    expiresAt: '2099-07-10T12:00:00.000Z',
    refreshExpiresAt: '2099-08-10T12:00:00.000Z',
    refreshToken: 'r'.repeat(48),
    tokenType: 'Bearer',
  };
}

function expiredAccessSession(): AuthSession {
  return {
    ...freshSession(),
    expiresAt: '2020-07-10T12:00:00.000Z',
  };
}

function rotatedSession(accessToken: string): AuthSession {
  return {
    ...freshSession(),
    accessToken,
    refreshToken: accessToken.padEnd(48, 'x'),
  };
}
