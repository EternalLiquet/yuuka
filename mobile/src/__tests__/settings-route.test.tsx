import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import SettingsScreen from '../../app/(tabs)/settings';

const mockSignOut = jest.fn();
const mockUpdateSettings = jest.fn();
const mockSettings = {
  apiBaseUrl: 'https://yuuka.test/api/v1',
  currencyCode: 'USD',
  theme: 'dark',
  timezone: 'America/Indianapolis',
};

jest.mock('@/auth/auth-provider', () => ({
  useAuth: () => ({ signOut: mockSignOut }),
}));

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: mockSettings, updateSettings: mockUpdateSettings }),
}));

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return (
      <SafeAreaProvider
        initialMetrics={{
          frame: { height: 844, width: 390, x: 0, y: 0 },
          insets: { bottom: 0, left: 0, right: 0, top: 0 },
        }}
      >
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </SafeAreaProvider>
    );
  };
}

function queryClient() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
}

function response(body: unknown, ok = true, status = 200): Response {
  return {
    headers: { get: () => 'application/json' } as unknown as Headers,
    json: jest.fn().mockResolvedValue(body),
    ok,
    status,
  } as unknown as Response;
}

describe('Settings route version footer', () => {
  let fetchMock: jest.SpiedFunction<typeof fetch>;

  beforeEach(() => {
    jest.resetAllMocks();
    fetchMock = jest.spyOn(globalThis, 'fetch').mockImplementation(async (url) => {
      const target = String(url);
      if (target === 'https://yuuka.test/health/ready') return response({ status: 'UP' });
      if (target === 'https://yuuka.test/health/version') return response({ version: '1.2.3' });
      return response({ message: 'not found' }, false, 404);
    });
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('loads the backend version from the API origin and formats a normal release', async () => {
    const client = queryClient();
    const view = await render(<SettingsScreen />, { wrapper: wrapper(client) });

    await waitFor(() => expect(view.getByText('Yuuka v1.2.3')).toBeTruthy());

    const urls = fetchMock.mock.calls.map(([url]) => String(url));
    expect(urls).toContain('https://yuuka.test/health/version');
    expect(urls).toContain('https://yuuka.test/health/ready');
    expect(urls).not.toContain('https://yuuka.test/api/v1/version');
    expect(urls).not.toContain('https://yuuka.test/api/v1/health/version');
    client.clear();
  });

  it('shows compact loading and unavailable footer states', async () => {
    let resolveVersion: (response: Response) => void = () => undefined;
    fetchMock.mockImplementation(async (url) => {
      const target = String(url);
      if (target.endsWith('/health/ready')) return response({ status: 'UP' });
      return new Promise<Response>((resolve) => {
        resolveVersion = resolve;
      });
    });
    const client = queryClient();
    const view = await render(<SettingsScreen />, { wrapper: wrapper(client) });

    expect(view.getByText('Yuuka · Checking version')).toBeTruthy();
    await act(async () => {
      resolveVersion(response({ message: 'missing' }, false, 404));
    });
    await waitFor(() => expect(view.getByText('Yuuka version unavailable')).toBeTruthy());
    client.clear();
  });

  it('uses the saved API URL when Check now runs and refetches readiness plus version', async () => {
    const client = queryClient();
    const view = await render(<SettingsScreen />, { wrapper: wrapper(client) });

    await waitFor(() => expect(view.getByText('Yuuka v1.2.3')).toBeTruthy());
    await fireEvent.changeText(view.getByLabelText('API base URL'), 'https://draft.test/api/v1');
    await fireEvent.press(view.getByLabelText('Check now'));

    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(4));
    const urls = fetchMock.mock.calls.map(([url]) => String(url));
    expect(urls.filter((url) => url === 'https://yuuka.test/health/ready')).toHaveLength(2);
    expect(urls.filter((url) => url === 'https://yuuka.test/health/version')).toHaveLength(2);
    expect(urls.some((url) => url.includes('draft.test'))).toBe(false);
    client.clear();
  });

  it('keeps the last successful version visible after a version refetch failure', async () => {
    let versionRequests = 0;
    fetchMock.mockImplementation(async (url) => {
      const target = String(url);
      if (target.endsWith('/health/ready')) return response({ status: 'UP' });
      versionRequests += 1;
      if (versionRequests === 1) return response({ version: 'v1.2.3' });
      return response({ message: 'old backend' }, false, 404);
    });
    const client = queryClient();
    const view = await render(<SettingsScreen />, { wrapper: wrapper(client) });

    await waitFor(() => expect(view.getByText('Yuuka v1.2.3')).toBeTruthy());
    await fireEvent.press(view.getByLabelText('Check now'));

    await waitFor(() => expect(versionRequests).toBe(2));
    expect(view.getByText('Yuuka v1.2.3')).toBeTruthy();
    client.clear();
  });
});
