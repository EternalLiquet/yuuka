import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { BudgetTemplate } from '@/api/contracts';

import TemplatesScreen from '../../app/(tabs)/templates';

jest.mock('react-native/Libraries/Components/RefreshControl/RefreshControl', () => ({
  __esModule: true,
  default: 'RefreshControl',
}));

const mockPush = jest.fn();
const mockApi = {
  templates: jest.fn(),
};

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
}));

jest.mock('@/api/use-yuuka-api', () => ({
  useYuukaApi: () => mockApi,
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

jest.mock('@/components/yuuka-mascot', () => {
  const React = require('react');
  const { Text } = require('react-native');
  return {
    YuukaMascot: ({ testID }: { testID?: string }) =>
      React.createElement(Text, { testID }, 'Yuuka'),
  };
});

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

function client() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
}

describe('Templates tab', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.templates.mockResolvedValue(templatePage([template()]));
  });

  it('shows the cold loader, list metrics, and navigates to template detail', async () => {
    const queryClient = client();
    const view = await render(<TemplatesScreen />, { wrapper: wrapper(queryClient) });

    expect(view.getByText('Loading templates...')).toBeTruthy();
    await waitFor(() => expect(view.getByText('Rent 1')).toBeTruthy());
    expect(view.getByText('$1,200.00')).toBeTruthy();

    await fireEvent(view.getByTestId('templates-refresh-control'), 'refresh');
    expect(mockApi.templates).toHaveBeenCalledTimes(2);
    expect(view.getByTestId('templates-refresh-control').props.tintColor).toBe('transparent');

    fireEvent.press(view.getByLabelText('Open template Rent 1'));
    expect(mockPush).toHaveBeenCalledWith('/templates/11111111-1111-4111-8111-111111111201');
    queryClient.clear();
  });

  it('shows empty state with a create action', async () => {
    mockApi.templates.mockResolvedValueOnce(templatePage([]));
    const queryClient = client();
    const view = await render(<TemplatesScreen />, { wrapper: wrapper(queryClient) });

    await waitFor(() => expect(view.getByText('No templates yet')).toBeTruthy());
    fireEvent.press(view.getAllByLabelText('New template')[0]);
    expect(mockPush).toHaveBeenCalledWith('/templates/new');
    queryClient.clear();
  });

  it('shows error retry and preserves stale cached data during refetch errors', async () => {
    mockApi.templates.mockRejectedValueOnce(new Error('Network unavailable'));
    const queryClient = client();
    const view = await render(<TemplatesScreen />, { wrapper: wrapper(queryClient) });

    await waitFor(() => expect(view.getByText('Could not load')).toBeTruthy());
    fireEvent.press(view.getByLabelText('Retry'));
    expect(mockApi.templates).toHaveBeenCalledTimes(2);
    view.unmount();
    queryClient.clear();

    const staleClient = client();
    staleClient.setQueryData(['templates'], templatePage([template()]));
    mockApi.templates.mockRejectedValueOnce(new Error('Offline'));
    const staleView = await render(<TemplatesScreen />, { wrapper: wrapper(staleClient) });

    await waitFor(() => expect(staleView.getByText('Rent 1')).toBeTruthy());
    await waitFor(() =>
      expect(staleView.getByText('Showing saved data. Reconnect to refresh.')).toBeTruthy(),
    );
    staleClient.clear();
  });
});

function template(overrides: Partial<BudgetTemplate> = {}): BudgetTemplate {
  return {
    archived: false,
    archivedAt: null,
    createdAt: '2026-07-12T12:00:00Z',
    defaultTotalMinor: 120000,
    description: 'Repeat rent check',
    entries: [],
    entryCount: 2,
    id: '11111111-1111-4111-8111-111111111201',
    name: 'Rent 1',
    updatedAt: '2026-07-13T12:00:00Z',
    version: 0,
    ...overrides,
  };
}

function templatePage(items: BudgetTemplate[]) {
  return {
    hasNext: false,
    items,
    page: 0,
    size: items.length,
    totalItems: items.length,
    totalPages: items.length ? 1 : 0,
  };
}
