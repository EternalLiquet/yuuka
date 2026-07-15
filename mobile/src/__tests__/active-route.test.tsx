/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { AppState, StyleSheet } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Paycheck, RollingSpendingBucketPerformance } from '@/api/contracts';

import ActiveScreen from '../../app/(tabs)/active';

const mockPush = jest.fn();
let mockFocusCallback: (() => void) | null = null;
let mockAppStateListener: ((state: string) => void) | null = null;
let mockTimezone = 'America/Indianapolis';
const mockApi = {
  activePaychecks: jest.fn(),
  rollingSpendingBucketPerformance: jest.fn(),
};
const queryClients: QueryClient[] = [];
const rollingTitle30 = 'Spending Buckets · Last 30 days';
const rollingTitle90 = 'Spending Buckets · Last 90 days';

jest.mock('expo-router', () => {
  const React = require('react');
  return {
    useFocusEffect: (callback: () => void) => {
      mockFocusCallback = callback;
      React.useEffect(() => callback(), [callback]);
    },
    useRouter: () => ({ push: mockPush }),
  };
});

jest.mock('react-native/Libraries/Components/RefreshControl/RefreshControl', () => {
  const React = require('react');
  const { Pressable } = require('react-native');
  return {
    __esModule: true,
    default: ({ onRefresh }: { onRefresh: () => void }) =>
      React.createElement(Pressable, {
        accessibilityLabel: 'Refresh active paychecks',
        onPress: onRefresh,
      }),
  };
});

jest.mock('@/api/use-yuuka-api', () => ({
  useYuukaApi: () => mockApi,
}));

jest.mock('@/hooks/use-minimum-visible-duration', () => ({
  useMinimumVisibleDuration: () => false,
}));

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: {
      apiBaseUrl: 'http://localhost:8080/api/v1',
      currencyCode: 'USD',
      theme: 'dark',
      timezone: mockTimezone,
    },
  }),
}));

function routeWrapper(queryClient: QueryClient, width = 390) {
  return function Wrapper({ children }: PropsWithChildren) {
    return (
      <SafeAreaProvider
        initialMetrics={{
          frame: { height: 844, width, x: 0, y: 0 },
          insets: { bottom: 0, left: 0, right: 0, top: 0 },
        }}
      >
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </SafeAreaProvider>
    );
  };
}

async function renderRoute(width?: number) {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  queryClients.push(queryClient);
  return {
    queryClient,
    view: await render(<ActiveScreen />, { wrapper: routeWrapper(queryClient, width) }),
  };
}

const paycheck: Paycheck = {
  allocatedMinor: 5000,
  allocationPercent: 100,
  amountMinor: 5000,
  archivedAt: null,
  closedAt: null,
  completionPercent: 0,
  createdAt: '2026-07-10T12:00:00Z',
  entries: [],
  id: '11111111-1111-4111-8111-111111111110',
  incomeDate: '2026-07-17',
  name: 'Active Check',
  notPaidCount: 1,
  notPaidMinor: 5000,
  notes: null,
  postedCount: 0,
  postedMinor: 0,
  processingCount: 0,
  processingMinor: 0,
  reopenedAt: null,
  requiresAttention: true,
  spendingBucketPerformance: null,
  source: null,
  state: 'ACTIVE',
  templateSourceId: null,
  unallocatedMinor: 0,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 1,
};

const page = {
  hasNext: false,
  items: [paycheck],
  page: 0,
  size: 100,
  totalItems: 1,
  totalPages: 1,
};

const rollingSummary: RollingSpendingBucketPerformance = {
  asOfDate: '2026-07-14',
  paycheckCount: 2,
  summary: {
    budgetedMinor: 10000,
    netMinor: 2500,
    spentMinor: 7500,
  },
  windowEndDate: '2026-07-14',
  windowStartDate: '2026-06-15',
};

const rolling90Summary: RollingSpendingBucketPerformance = {
  asOfDate: '2026-07-14',
  paycheckCount: 6,
  summary: {
    budgetedMinor: 30000,
    netMinor: -5000,
    spentMinor: 35000,
  },
  windowEndDate: '2026-07-14',
  windowStartDate: '2026-04-16',
};

function rollingSummaryWith(
  summary: RollingSpendingBucketPerformance['summary'],
): RollingSpendingBucketPerformance {
  return {
    ...rollingSummary,
    summary,
  };
}

describe('active route bucket performance', () => {
  afterEach(() => {
    cleanup();
    jest.restoreAllMocks();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockTimezone = 'America/Indianapolis';
    mockFocusCallback = null;
    mockAppStateListener = null;
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      mockAppStateListener = listener as (state: string) => void;
      return { remove: jest.fn() };
    });
    mockApi.activePaychecks.mockResolvedValue(page);
    mockApi.rollingSpendingBucketPerformance.mockImplementation((days: 30 | 90 = 30) =>
      Promise.resolve(days === 90 ? rolling90Summary : rollingSummary),
    );
  });

  it('shows a loading bucket card without blocking the paycheck list', async () => {
    let resolveSummary: (summary: RollingSpendingBucketPerformance) => void = () => undefined;
    const pendingSummary = new Promise<RollingSpendingBucketPerformance>((resolve) => {
      resolveSummary = resolve;
    });
    mockApi.rollingSpendingBucketPerformance.mockReturnValue(pendingSummary);

    const { view } = await renderRoute();

    expect(await view.findByText('Active Check')).toBeTruthy();
    expect(view.getByText(rollingTitle30)).toBeTruthy();
    expect(view.getByText('Loading bucket summary...')).toBeTruthy();

    await act(async () => {
      resolveSummary(rollingSummary);
      await pendingSummary;
    });
  });

  it('defaults to the current thirty-day rolling summary without a client-derived date', async () => {
    await renderRoute();

    await waitFor(() => expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalled());
    expect(mockApi.rollingSpendingBucketPerformance.mock.calls).toEqual(
      expect.arrayContaining([[30]]),
    );
    expect(
      mockApi.rollingSpendingBucketPerformance.mock.calls.every((call) => call[0] === 30),
    ).toBe(true);
  });

  it('renders the rolling summary when bucket data exists', async () => {
    const { view } = await renderRoute();

    expect(await view.findByText('Net under by $25.00')).toBeTruthy();
    expect(view.getByText(rollingTitle30)).toBeTruthy();
    expect(view.getByLabelText('Spending buckets last 30 days: Net under by $25.00')).toBeTruthy();
    expect(view.getByText('Budgeted')).toBeTruthy();
    expect(view.getByText('$100.00')).toBeTruthy();
    expect(view.getByText('Spent')).toBeTruthy();
    expect(view.getByText('$75.00')).toBeTruthy();
  });

  it('switches to ninety days and renders the selected-period result', async () => {
    const { view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();

    fireEvent.press(view.getByTestId('segmented-Spending bucket period-90'));

    await waitFor(() =>
      expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenLastCalledWith(90),
    );
    expect(await view.findByText('Net over by $50.00')).toBeTruthy();
    expect(view.getByText(rollingTitle90)).toBeTruthy();
    expect(view.getByLabelText('Spending buckets last 90 days: Net over by $50.00')).toBeTruthy();
    expect(view.getByText('Budgeted')).toBeTruthy();
    expect(view.getByText('$300.00')).toBeTruthy();
    expect(view.getByText('Spent')).toBeTruthy();
    expect(view.getByText('$350.00')).toBeTruthy();
  });

  it('switches back to thirty days using cached data while refetching', async () => {
    const { view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();

    fireEvent.press(view.getByTestId('segmented-Spending bucket period-90'));
    expect(await view.findByText('Net over by $50.00')).toBeTruthy();

    fireEvent.press(view.getByTestId('segmented-Spending bucket period-30'));

    await waitFor(() =>
      expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenLastCalledWith(30),
    );
    expect(view.getByText(rollingTitle30)).toBeTruthy();
    expect(view.getByText('Net under by $25.00')).toBeTruthy();
  });

  it('renders exactly-on-budget rolling text without losing accessibility text', async () => {
    mockApi.rollingSpendingBucketPerformance.mockResolvedValue(
      rollingSummaryWith({
        budgetedMinor: 123456789,
        netMinor: 0,
        spentMinor: 123456789,
      }),
    );

    const { view } = await renderRoute();

    expect(await view.findByText('Net exactly on budget')).toBeTruthy();
    expect(
      view.getByLabelText('Spending buckets last 30 days: Net exactly on budget'),
    ).toBeTruthy();
    expect(view.getByText('Budgeted')).toBeTruthy();
    expect(view.getByText('Spent')).toBeTruthy();
    expect(view.getAllByText('$1,234,567.89')).toHaveLength(2);
  });

  it('renders long under-budget amounts in a stacked narrow layout', async () => {
    mockApi.rollingSpendingBucketPerformance.mockResolvedValue(
      rollingSummaryWith({
        budgetedMinor: 123456789,
        netMinor: 123456789,
        spentMinor: 0,
      }),
    );

    const { view } = await renderRoute(320);
    const net = await view.findByText('Net under by $1,234,567.89');
    const title = view.getByText(rollingTitle30);
    const titleNetContainerStyle = StyleSheet.flatten(title.parent?.props.style);

    expect(net).toBeTruthy();
    expect(title.parent).toBe(net.parent);
    expect(titleNetContainerStyle?.flexDirection).not.toBe('row');
    expect(view.getByText('Budgeted')).toBeTruthy();
    expect(view.getByText('$1,234,567.89')).toBeTruthy();
    expect(view.getByText('Spent')).toBeTruthy();
    expect(view.getAllByText('$0.00').length).toBeGreaterThan(0);
  });

  it('renders long over-budget amounts below the title', async () => {
    mockApi.rollingSpendingBucketPerformance.mockResolvedValue(
      rollingSummaryWith({
        budgetedMinor: 0,
        netMinor: -123456789,
        spentMinor: 123456789,
      }),
    );

    const { view } = await renderRoute(320);
    const net = await view.findByText('Net over by $1,234,567.89');
    const title = view.getByText(rollingTitle30);
    const titleNetContainerStyle = StyleSheet.flatten(title.parent?.props.style);

    expect(net).toBeTruthy();
    expect(title.parent).toBe(net.parent);
    expect(titleNetContainerStyle?.flexDirection).not.toBe('row');
    expect(view.getByText('Budgeted')).toBeTruthy();
    expect(view.getAllByText('$0.00').length).toBeGreaterThan(0);
    expect(view.getByText('Spent')).toBeTruthy();
    expect(view.getByText('$1,234,567.89')).toBeTruthy();
  });

  it('refetches the rolling summary when the screen refocuses', async () => {
    const { view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();
    const callsBeforeFocus = mockApi.rollingSpendingBucketPerformance.mock.calls.length;

    await act(async () => {
      mockFocusCallback?.();
    });

    await waitFor(() =>
      expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(callsBeforeFocus + 1),
    );
    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenLastCalledWith(30);
  });

  it('does not change the financial reporting request when the local timezone setting changes', async () => {
    const { view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();
    const callsBeforeTimezoneChange = mockApi.rollingSpendingBucketPerformance.mock.calls.length;

    mockTimezone = 'America/Los_Angeles';
    await act(async () => {
      view.rerender(<ActiveScreen />);
    });

    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(
      callsBeforeTimezoneChange,
    );
    expect(
      mockApi.rollingSpendingBucketPerformance.mock.calls.every((call) => call[0] === 30),
    ).toBe(true);
  });

  it('refetches the rolling summary when the app returns to the foreground', async () => {
    const { view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();
    const callsBeforeForeground = mockApi.rollingSpendingBucketPerformance.mock.calls.length;

    await act(async () => {
      mockAppStateListener?.('active');
    });

    await waitFor(() =>
      expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(
        callsBeforeForeground + 1,
      ),
    );
    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenLastCalledWith(30);
  });

  it('refreshes active paychecks and rolling performance on pull-to-refresh', async () => {
    const { view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();
    const paycheckCallsBeforeRefresh = mockApi.activePaychecks.mock.calls.length;
    const rollingCallsBeforeRefresh = mockApi.rollingSpendingBucketPerformance.mock.calls.length;

    fireEvent.press(view.getByLabelText('Refresh active paychecks'));

    await waitFor(() =>
      expect(mockApi.activePaychecks).toHaveBeenCalledTimes(paycheckCallsBeforeRefresh + 1),
    );
    await waitFor(() =>
      expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(
        rollingCallsBeforeRefresh + 1,
      ),
    );
    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenLastCalledWith(30);
  });

  it('hides the card when the rolling summary has no qualifying bucket data', async () => {
    mockApi.rollingSpendingBucketPerformance.mockResolvedValue({
      ...rollingSummary,
      paycheckCount: 0,
      summary: null,
    });

    const { view } = await renderRoute();

    expect(await view.findByText('Active Check')).toBeTruthy();
    await waitFor(() => expect(view.queryByText(rollingTitle30)).toBeNull());
  });

  it('does not block the paycheck list when the rolling summary fails before data exists', async () => {
    mockApi.rollingSpendingBucketPerformance.mockRejectedValue(new Error('offline'));

    const { view } = await renderRoute();

    expect(await view.findByText('Active Check')).toBeTruthy();
    await waitFor(() => expect(view.queryByText(rollingTitle30)).toBeNull());
  });

  it('does not break the active list when the newly selected period fails', async () => {
    mockApi.rollingSpendingBucketPerformance.mockImplementation((days: 30 | 90 = 30) =>
      days === 90 ? Promise.reject(new Error('offline')) : Promise.resolve(rollingSummary),
    );
    const { view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();

    fireEvent.press(view.getByTestId('segmented-Spending bucket period-90'));

    await waitFor(() =>
      expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenLastCalledWith(90),
    );
    expect(view.getByText('Active Check')).toBeTruthy();
  });

  it('keeps the cached rolling summary visible when a refetch fails', async () => {
    const { queryClient, view } = await renderRoute();
    expect(await view.findByText('Net under by $25.00')).toBeTruthy();

    mockApi.rollingSpendingBucketPerformance.mockRejectedValue(new Error('offline'));
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['spending-buckets'] });
    });

    expect(view.getByText('Net under by $25.00')).toBeTruthy();
    expect(await view.findByText('Summary may be stale')).toBeTruthy();
  });
});
