import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Payback } from '@/api/contracts';

import PaybacksScreen from '../../app/(tabs)/paybacks';

jest.mock('react-native/Libraries/Components/RefreshControl/RefreshControl', () => ({
  __esModule: true,
  default: 'RefreshControl',
}));

const mockPush = jest.fn();
const mockApi = {
  paybacks: jest.fn(),
  reorderPaybacks: jest.fn(),
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

describe('Paybacks tab', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.paybacks.mockResolvedValue({
      items: [
        payback({ id: '11111111-1111-4111-8111-111111111101', name: 'Personal', position: 0 }),
        payback({ id: '11111111-1111-4111-8111-111111111102', name: 'Car repair', position: 1 }),
        payback({
          id: '11111111-1111-4111-8111-111111111103',
          name: 'Old loan',
          position: 2,
          state: 'PAID_OFF',
        }),
      ],
      summary: {
        activeCount: 2,
        totalOriginalMinor: 30000,
        totalRemainingMinor: 20000,
        totalRepaidMinor: 10000,
      },
    });
    mockApi.reorderPaybacks.mockResolvedValue({
      items: [],
      summary: {
        activeCount: 0,
        totalOriginalMinor: 0,
        totalRemainingMinor: 0,
        totalRepaidMinor: 0,
      },
    });
  });

  it('moves Paybacks using the persisted custom order contract', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    const view = await render(<PaybacksScreen />, { wrapper: wrapper(queryClient) });

    await waitFor(() => expect(view.getByText('Personal')).toBeTruthy(), { timeout: 2000 });
    fireEvent.press(view.getByLabelText('Move Car repair up'));

    await waitFor(() => expect(mockApi.reorderPaybacks).toHaveBeenCalled());
    expect(mockApi.reorderPaybacks).toHaveBeenCalledWith([
      '11111111-1111-4111-8111-111111111102',
      '11111111-1111-4111-8111-111111111101',
      '11111111-1111-4111-8111-111111111103',
    ]);
    await view.unmount();
    queryClient.clear();
  });

  it('keeps cached content usable and shows the non-blocking mascot during a background refetch', async () => {
    let resolveRefetch: (value: ReturnType<typeof paybacksResponse>) => void = () => undefined;
    mockApi.paybacks.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveRefetch = resolve;
      }),
    );
    const client = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    client.setQueryData(['paybacks'], paybacksResponse());

    const view = await render(<PaybacksScreen />, { wrapper: wrapper(client) });

    expect(view.getByText('Personal')).toBeTruthy();
    expect(view.queryByText('Loading Paybacks...')).toBeNull();
    await waitFor(() => expect(view.getByTestId('yuuka-refresh-indicator')).toBeTruthy());
    expect(view.getByLabelText('New Payback').props.disabled).not.toBe(true);

    await act(async () => resolveRefetch(paybacksResponse()));
    await waitFor(() => expect(view.queryByTestId('yuuka-refresh-indicator')).toBeNull(), {
      timeout: 2000,
    });
    await view.unmount();
    client.clear();
  });

  it('pull-to-refresh triggers one refetch, preserves content, and releases native refresh state', async () => {
    const client = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    const view = await render(<PaybacksScreen />, { wrapper: wrapper(client) });
    await waitFor(() => expect(view.getByText('Personal')).toBeTruthy(), { timeout: 2000 });
    let resolveRefresh: (value: ReturnType<typeof paybacksResponse>) => void = () => undefined;
    mockApi.paybacks.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveRefresh = resolve;
      }),
    );

    await fireEvent(view.getByTestId('paybacks-refresh-control'), 'refresh');

    expect(mockApi.paybacks).toHaveBeenCalledTimes(2);
    expect(view.getByText('Personal')).toBeTruthy();
    await waitFor(() => expect(view.getByTestId('yuuka-refresh-indicator')).toBeTruthy());
    expect(view.getByTestId('paybacks-refresh-control').props.tintColor).toBe('transparent');

    await act(async () => resolveRefresh(paybacksResponse()));
    await waitFor(() =>
      expect(view.getByTestId('paybacks-refresh-control').props.refreshing).toBe(false),
    );
    await waitFor(() => expect(view.queryByTestId('yuuka-refresh-indicator')).toBeNull(), {
      timeout: 2000,
    });
    await view.unmount();
    client.clear();
  });
});

function paybacksResponse() {
  return {
    items: [
      payback({ id: '11111111-1111-4111-8111-111111111101', name: 'Personal', position: 0 }),
      payback({ id: '11111111-1111-4111-8111-111111111102', name: 'Car repair', position: 1 }),
    ],
    summary: {
      activeCount: 2,
      totalOriginalMinor: 20000,
      totalRemainingMinor: 20000,
      totalRepaidMinor: 0,
    },
  };
}

function payback(overrides: Partial<Payback> = {}): Payback {
  return {
    borrowedDate: '2026-07-12',
    createdAt: '2026-07-12T12:00:00Z',
    id: '11111111-1111-4111-8111-111111111101',
    name: 'Personal',
    notes: null,
    openingRemainingAmountMinor: 10000,
    originalAmountMinor: 10000,
    position: 0,
    progressPercent: 0,
    remainingMinor: 10000,
    repaidMinor: 0,
    repaymentCount: 0,
    source: null,
    state: 'ACTIVE',
    updatedAt: '2026-07-12T12:30:00Z',
    version: 0,
    ...overrides,
  };
}
