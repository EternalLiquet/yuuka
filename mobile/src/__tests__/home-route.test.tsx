/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { AppState, StyleSheet } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { DashboardSummary, RollingSpendingBucketPerformance } from '@/api/contracts';

import HomeScreen from '../../app/(tabs)/home';

const mockPush = jest.fn();
const queryClients: QueryClient[] = [];
const mockApi = {
  dashboardSummary: jest.fn(),
  me: jest.fn(),
  recurringBillTimeline: jest.fn(),
  rollingSpendingBucketPerformance: jest.fn(),
};

jest.mock('expo-router', () => {
  return {
    useFocusEffect: () => undefined,
    useRouter: () => ({ push: mockPush }),
  };
});

jest.mock('react-native/Libraries/Components/RefreshControl/RefreshControl', () => {
  const React = require('react');
  const { Pressable } = require('react-native');
  return {
    __esModule: true,
    default: ({
      accessibilityLabel,
      onRefresh,
    }: {
      accessibilityLabel: string;
      onRefresh: () => void;
    }) => React.createElement(Pressable, { accessibilityLabel, onPress: onRefresh }),
  };
});

jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: {
      apiBaseUrl: 'http://localhost:8080/api/v1',
      currencyCode: 'USD',
      recurringBillSuggestionDays: 7,
      theme: 'dark',
      timezone: 'America/Indianapolis',
    },
  }),
}));

const paycheckId = '11111111-1111-4111-8111-111111111101';
const entryId = '11111111-1111-4111-8111-111111111102';
const ledgerId = '11111111-1111-4111-8111-111111111103';
const definitionId = '11111111-1111-4111-8111-111111111104';

const summary: DashboardSummary = {
  active: {
    notPaidEntryCount: 2,
    paycheckCount: 1,
    previews: [
      {
        amountMinor: 50000,
        incomeDate: '2026-07-18',
        name: 'Friday paycheck',
        notPaidCount: 2,
        paycheckId,
        processingCount: 1,
        unallocatedMinor: 12500,
      },
    ],
    processingEntryCount: 1,
    totalUnallocatedMinor: 12500,
  },
  asOfDate: '2026-07-20',
  expenseLists: { finalizedCount: 1, openCount: 2 },
  needsAttention: [
    {
      amountMinor: 4500,
      attentionSinceDate: '2026-07-16',
      dueDate: null,
      entryId,
      expenseLedgerId: null,
      kind: 'PROCESSING_ENTRY',
      name: 'Electricity',
      paycheckId,
    },
    {
      amountMinor: 8200,
      attentionSinceDate: '2026-07-18',
      dueDate: null,
      entryId: null,
      expenseLedgerId: ledgerId,
      kind: 'FINALIZED_EXPENSE_LEDGER',
      name: 'Trip expenses',
      paycheckId: null,
    },
  ],
  paybacks: { activeCount: 2, totalRemainingMinor: 30000 },
  plannedSavings: { activeCount: 3, totalActiveReservedBalanceMinor: 22500 },
};

const rolling: RollingSpendingBucketPerformance = {
  asOfDate: '2026-07-20',
  paycheckCount: 2,
  summary: { budgetedMinor: 10000, netMinor: 2500, spentMinor: 7500 },
  windowEndDate: '2026-07-20',
  windowStartDate: '2026-06-21',
};

const recurring = {
  from: '2026-07-20',
  items: [
    {
      accountName: null,
      definitionId,
      definitionVersion: 1,
      importCount: 0,
      imports: [],
      name: 'Internet',
      notes: null,
      occurrenceDate: '2026-07-22',
      payee: null,
      paymentMethod: 'AUTOPAY' as const,
      typicalAmountMinor: 6500,
    },
  ],
  through: '2026-07-27',
};

function wrapper(client: QueryClient, width = 390) {
  return function Wrapper({ children }: PropsWithChildren) {
    return (
      <SafeAreaProvider
        initialMetrics={{
          frame: { height: 844, width, x: 0, y: 0 },
          insets: { bottom: 0, left: 0, right: 0, top: 0 },
        }}
      >
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </SafeAreaProvider>
    );
  };
}

async function renderHome(width?: number, client?: QueryClient) {
  const queryClient =
    client ??
    new QueryClient({
      defaultOptions: { queries: { gcTime: Infinity, retry: false } },
    });
  queryClients.push(queryClient);
  return {
    client: queryClient,
    view: await render(<HomeScreen />, { wrapper: wrapper(queryClient, width) }),
  };
}

describe('Home dashboard', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((client) => client.clear());
    queryClients.length = 0;
    jest.restoreAllMocks();
  });

  beforeEach(() => {
    mockPush.mockReset();
    Object.values(mockApi).forEach((mock) => mock.mockReset());
    jest.spyOn(AppState, 'addEventListener').mockReturnValue({ remove: jest.fn() });
    mockApi.dashboardSummary.mockResolvedValue(summary);
    mockApi.rollingSpendingBucketPerformance.mockResolvedValue(rolling);
    mockApi.me.mockResolvedValue({
      recurringBillSuggestionDays: 7,
      timezone: 'America/Indianapolis',
    });
    mockApi.recurringBillTimeline.mockResolvedValue(recurring);
  });

  it('renders the five Home sections, compact previews, recurring Bills, and financial rows', async () => {
    const { view } = await renderHome();

    await waitFor(() => expect(view.getByText('Friday paycheck')).toBeTruthy());
    expect(view.getByLabelText('Needs Attention section')).toBeTruthy();
    expect(view.getByLabelText('Active Paychecks section')).toBeTruthy();
    expect(view.getByLabelText('Spending Buckets section')).toBeTruthy();
    expect(view.getByLabelText('Upcoming Recurring Bills section')).toBeTruthy();
    expect(view.getByLabelText('Financial Positions section')).toBeTruthy();
    expect(view.getByText('Spending Buckets · Last 30 days')).toBeTruthy();
    expect(view.getByText('Internet')).toBeTruthy();
    expect(view.getByText('$300.00 · 2 active')).toBeTruthy();
    expect(view.getByText('$225.00 · 3 active')).toBeTruthy();
    expect(view.getByText('2 Open · 1 Finalized · ready to settle')).toBeTruthy();
    expect(view.getByLabelText('New paycheck').props.accessibilityRole).toBe('button');

    await fireEvent.press(view.getByLabelText('New paycheck'));
    expect(mockPush).toHaveBeenCalledWith('/paychecks/new');
  });

  it('pushes existing detail and top-level routes so Back can return to cached Home content', async () => {
    const { view } = await renderHome();
    await waitFor(() => expect(view.getByText('Electricity')).toBeTruthy());

    await fireEvent.press(view.getByLabelText(/Electricity/));
    await fireEvent.press(view.getByLabelText(/Trip expenses/));
    await fireEvent.press(view.getByLabelText('Open Friday paycheck paycheck'));
    await fireEvent.press(view.getByLabelText('View Timeline'));
    await fireEvent.press(view.getByLabelText('Open Planned Savings'));

    expect(mockPush).toHaveBeenNthCalledWith(1, {
      params: { highlightEntryId: entryId, id: paycheckId },
      pathname: '/paychecks/[id]',
    });
    expect(mockPush).toHaveBeenNthCalledWith(2, `/expense-ledgers/${ledgerId}`);
    expect(mockPush).toHaveBeenNthCalledWith(3, `/paychecks/${paycheckId}`);
    expect(mockPush).toHaveBeenNthCalledWith(4, '/recurring-bills');
    expect(mockPush).toHaveBeenNthCalledWith(5, '/(tabs)/planned-savings');
    expect(view.getByText('Friday paycheck')).toBeTruthy();
  });

  it('switches the rolling report between 30 and 90 days', async () => {
    mockApi.rollingSpendingBucketPerformance.mockImplementation(async (days: 30 | 90) => ({
      ...rolling,
      summary:
        days === 90 ? { budgetedMinor: 20000, netMinor: -500, spentMinor: 20500 } : rolling.summary,
    }));
    const { view } = await renderHome();
    await waitFor(() => expect(view.getByText('Spending Buckets · Last 30 days')).toBeTruthy());

    await fireEvent.press(view.getByTestId('segmented-Spending bucket period-90'));
    await waitFor(() =>
      expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenLastCalledWith(90),
    );
    expect(view.getByText('Spending Buckets · Last 90 days')).toBeTruthy();
    expect(view.getByText('Net over by $5.00')).toBeTruthy();
  });

  it('loads and retries query groups independently', async () => {
    mockApi.dashboardSummary.mockRejectedValue(new Error('summary offline'));
    const { view } = await renderHome();

    await waitFor(() => expect(view.getByLabelText('Retry dashboard summary')).toBeTruthy());
    expect(view.getByText('Spending Buckets · Last 30 days')).toBeTruthy();
    expect(view.getByText('Internet')).toBeTruthy();

    mockApi.dashboardSummary.mockResolvedValue(summary);
    await fireEvent.press(view.getByLabelText('Retry dashboard summary'));
    await waitFor(() => expect(view.getByText('Friday paycheck')).toBeTruthy());
  });

  it('keeps independently loaded groups visible while the summary is pending', async () => {
    mockApi.dashboardSummary.mockImplementation(() => new Promise(() => undefined));
    const { view } = await renderHome();

    expect(view.getByText('Loading summary...')).toBeTruthy();
    await waitFor(() => expect(view.getByText('Internet')).toBeTruthy());
    expect(view.getByText(/Spending Buckets.*Last 30 days/)).toBeTruthy();
  });

  it('keeps cached summary content visible when a background refresh fails', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { gcTime: Infinity, retry: false } },
    });
    client.setQueryData(['dashboard', 'summary'], summary);
    mockApi.dashboardSummary.mockRejectedValue(new Error('offline'));
    const { view } = await renderHome(undefined, client);

    expect(view.getByText('Friday paycheck')).toBeTruthy();
    await waitFor(() =>
      expect(view.getAllByText('Showing saved data. Reconnect to refresh.').length).toBeGreaterThan(
        0,
      ),
    );
  });

  it('pull-to-refresh refetches all query groups and tolerates a partial failure', async () => {
    const { view } = await renderHome();
    await waitFor(() => expect(view.getByText('Internet')).toBeTruthy());
    const summaryCalls = mockApi.dashboardSummary.mock.calls.length;
    const bucketCalls = mockApi.rollingSpendingBucketPerformance.mock.calls.length;
    const recurringCalls = mockApi.recurringBillTimeline.mock.calls.length;
    mockApi.dashboardSummary.mockRejectedValueOnce(new Error('temporary'));

    await fireEvent.press(view.getByLabelText('Refresh Home dashboard'));
    await waitFor(() => expect(mockApi.dashboardSummary).toHaveBeenCalledTimes(summaryCalls + 1));
    expect(mockApi.rollingSpendingBucketPerformance.mock.calls.length).toBeGreaterThan(bucketCalls);
    expect(mockApi.recurringBillTimeline.mock.calls.length).toBeGreaterThan(recurringCalls);
    expect(view.getByText('Internet')).toBeTruthy();
  });

  it('uses wrapping, shrinkable layouts on narrow screens and leaves text available to font scaling', async () => {
    const { view } = await renderHome(320);
    await waitFor(() => expect(view.getByText('Friday paycheck')).toBeTruthy());

    expect(StyleSheet.flatten(view.getByTestId('home-header').props.style)).toMatchObject({
      flexWrap: 'wrap',
    });
    expect(StyleSheet.flatten(view.getByTestId('home-active-metrics').props.style)).toMatchObject({
      flexWrap: 'wrap',
    });
    expect(view.getByText('$225.00 · 3 active').props.numberOfLines).toBeUndefined();
    expect(view.getByTestId('home-financial-positions')).toBeTruthy();
  });
});
