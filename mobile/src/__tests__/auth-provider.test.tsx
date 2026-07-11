import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { useState } from 'react';
import type { PropsWithChildren } from 'react';
import { Pressable, Text } from 'react-native';

import { AuthProvider, useAuth } from '@/auth/auth-provider';
import type { AuthSession } from '@/auth/session';

const mockClearSession = jest.fn();
const mockGetStoredSession = jest.fn();
const mockStoreSession = jest.fn();
const mockLogin = jest.fn();
const mockLogout = jest.fn();
const mockRefreshSession = jest.fn();

jest.mock('@/auth/auth-storage', () => ({
  clearSession: () => mockClearSession(),
  getStoredSession: () => mockGetStoredSession(),
  storeSession: (session: AuthSession) => mockStoreSession(session),
}));

jest.mock('@/auth/auth-api', () => ({
  login: (...args: unknown[]) => mockLogin(...args),
  logout: (...args: unknown[]) => mockLogout(...args),
  refreshSession: (...args: unknown[]) => mockRefreshSession(...args),
}));

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: {
      apiBaseUrl: 'http://localhost:8080/api/v1',
      currencyCode: 'USD',
      theme: 'dark',
      timezone: 'America/Indianapolis',
    },
  }),
}));

describe('AuthProvider session behavior', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockLogout.mockResolvedValue(undefined);
  });

  it('clears private query data when signing out', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    queryClient.setQueryData(['paychecks', 'active'], { items: ['private'] });
    mockGetStoredSession.mockResolvedValue(freshSession());

    const view = await renderWithAuth(queryClient);

    expect(await view.findByText('signed-in')).toBeTruthy();
    fireEvent.press(view.getByLabelText('Sign out'));

    await waitFor(() => expect(view.getByText('signed-out')).toBeTruthy());
    expect(mockLogout).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1',
      freshSession().refreshToken,
    );
    expect(mockClearSession).toHaveBeenCalled();
    expect(queryClient.getQueryData(['paychecks', 'active'])).toBeUndefined();
  });

  it('returns to sign-in state and clears cache when refresh fails', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    queryClient.setQueryData(['paycheck', 'private-id'], { name: 'Private' });
    mockGetStoredSession.mockResolvedValue(expiredAccessSession());
    mockRefreshSession.mockRejectedValue(new Error('Refresh failed'));

    const view = await renderWithAuth(queryClient);

    await waitFor(() => expect(view.getByText('signed-out')).toBeTruthy());
    expect(view.getByText('expired')).toBeTruthy();
    expect(mockClearSession).toHaveBeenCalled();
    expect(queryClient.getQueryData(['paycheck', 'private-id'])).toBeUndefined();
  });
});

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
  const auth = useAuth();
  const [status, setStatus] = useState('');
  if (auth.isLoading) {
    return <Text>loading</Text>;
  }
  return (
    <>
      <Text>{auth.session ? 'signed-in' : 'signed-out'}</Text>
      <Text>{auth.sessionExpired ? 'expired' : 'fresh'}</Text>
      <Text>{status}</Text>
      <Pressable
        accessibilityLabel="Sign out"
        onPress={() => {
          auth.signOut().then(() => setStatus('signed out'));
        }}
      >
        <Text>Sign out</Text>
      </Pressable>
    </>
  );
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
    expiresAt: '2026-07-10T12:00:00.000Z',
  };
}
