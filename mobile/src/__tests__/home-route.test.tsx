/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor, within } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { AppState, StyleSheet } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { DashboardSummary, RollingSpendingBucketPerformance } from '@/api/contracts';

import HomeScreen from '../../app/(tabs)/home';

const mockPush = jest.fn();
let mockFocusCallback: (() => void) | null = null;
let mockAppStateListener: ((state: string) => void) | null = null;
const queryClients: QueryClient[] = [];
const mockApi = {
  activePaychecks: jest.fn(),
  dashboardSummary: jest.fn(),
  importRecurringBills: jest.fn(),
  me: jest.fn(),
  paycheck: jest.fn(),
  recurringBillTimeline: jest.fn(),
  rollingSpendingBucketPerformance: jest.fn(),
};

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

const refreshedSummary: DashboardSummary = {
  ...summary,
  active: {
    ...summary.active,
    notPaidEntryCount: 7,
    paycheckCount: 4,
    processingEntryCount: 6,
    totalUnallocatedMinor: 32100,
  },
  needsAttention: [{ ...summary.needsAttention[0], name: 'Updated rent' }],
};

const refreshedRecurring = {
  ...recurring,
  items: [
    {
      ...recurring.items[0],
      name: 'Insurance',
      occurrenceDate: '2026-07-23',
      paymentMethod: 'MANUAL' as const,
      typicalAmountMinor: 7100,
    },
  ],
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
    mockFocusCallback = null;
    mockAppStateListener = null;
    Object.values(mockApi).forEach((mock) => mock.mockReset());
    jest.spyOn(AppState, 'addEventListener').mockImplementation((_event, listener) => {
      mockAppStateListener = listener as (state: string) => void;
      return { remove: jest.fn() };
    });
    mockApi.dashboardSummary.mockResolvedValue(summary);
    mockApi.rollingSpendingBucketPerformance.mockResolvedValue(rolling);
    mockApi.me.mockResolvedValue({
      recurringBillSuggestionDays: 7,
      timezone: 'America/Indianapolis',
    });
    mockApi.recurringBillTimeline.mockResolvedValue(recurring);
    mockApi.activePaychecks.mockResolvedValue({
      items: [],
      page: 0,
      size: 100,
      totalItems: 0,
      totalPages: 0,
      hasNext: false,
    });
    mockApi.paycheck.mockResolvedValue({});
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
    await fireEvent.press(view.getByLabelText('View Insights'));
    expect(mockPush).toHaveBeenCalledWith('/spending-buckets/insights');
    expect(view.getByText('Internet')).toBeTruthy();
    expect(view.getByText(/1 due in the next 7 days/)).toBeTruthy();
    expect(view.getByText(/1 not added to a paycheck/)).toBeTruthy();
    expect(view.getByText('Not added to a paycheck')).toBeTruthy();
    expect(view.getByText('$300.00 · 2 active')).toBeTruthy();
    expect(view.getByText('$225.00 · 3 active')).toBeTruthy();
    expect(view.getByText('2 Open · 1 Finalized · ready to settle')).toBeTruthy();
    expect(view.getByLabelText('New paycheck').props.accessibilityRole).toBe('button');

    await fireEvent.press(view.getByLabelText('New paycheck'));
    expect(mockPush).toHaveBeenCalledWith('/paychecks/new');
  });

  it('counts the full window, caps rows at three, and opens assigned imports', async () => {
    const imports = [
      { entryId, paycheckId, paycheckName: 'Rent 1/2', status: 'NOT_PAID' as const },
    ];
    mockApi.recurringBillTimeline.mockResolvedValue({
      ...recurring,
      items: [
        { ...recurring.items[0], imports, importCount: 1, name: 'Rent' },
        {
          ...recurring.items[0],
          definitionId: '11111111-1111-4111-8111-111111111105',
          name: 'Utilities',
          occurrenceDate: '2026-07-23',
        },
        {
          ...recurring.items[0],
          definitionId: '11111111-1111-4111-8111-111111111106',
          name: 'Water',
          occurrenceDate: '2026-07-24',
        },
        {
          ...recurring.items[0],
          definitionId: '11111111-1111-4111-8111-111111111107',
          name: 'Phone',
          occurrenceDate: '2026-07-25',
        },
      ],
    });
    const { view } = await renderHome();
    expect(await view.findByText(/4 due in the next 7 days/)).toBeTruthy();
    expect(view.getByText(/3 not added to a paycheck/)).toBeTruthy();
    expect(view.queryByText('Phone')).toBeNull();
    expect(view.getByText('Added to Rent 1/2 · Not Paid')).toBeTruthy();

    await fireEvent.press(view.getByLabelText(/Rent.*Added to Rent 1\/2.*Not Paid/));
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/paychecks/[id]',
      params: { highlightEntryId: entryId, id: paycheckId },
    });
  });

  it('reviews multiple imports and opens the selected highlighted entry', async () => {
    const secondEntryId = '11111111-1111-4111-8111-111111111108';
    const secondPaycheckId = '11111111-1111-4111-8111-111111111109';
    mockApi.recurringBillTimeline.mockResolvedValue({
      ...recurring,
      items: [
        {
          ...recurring.items[0],
          importCount: 2,
          imports: [
            { entryId, paycheckId, paycheckName: 'Rent 1/2', status: 'PROCESSING' as const },
            {
              entryId: secondEntryId,
              paycheckId: secondPaycheckId,
              paycheckName: 'Rent 2/2',
              status: 'POSTED' as const,
            },
          ],
        },
      ],
    });
    const { view } = await renderHome();
    await fireEvent.press(await view.findByLabelText(/Internet.*Added to 2 paychecks/));
    expect(view.getByText('Review imports')).toBeTruthy();
    expect(view.getAllByText('Processing').length).toBeGreaterThan(0);
    expect(view.getByText('Posted')).toBeTruthy();
    await fireEvent.press(view.getByLabelText('Open Internet in Rent 2/2, Posted'));
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/paychecks/[id]',
      params: { highlightEntryId: secondEntryId, id: secondPaycheckId },
    });
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
    expect(await view.findByText('Net over by $5.00')).toBeTruthy();
  });

  it('refetches all groups on return focus and replaces stale summary and recurring data', async () => {
    const { view } = await renderHome();
    await waitFor(() => expect(view.getByText('Internet')).toBeTruthy());
    const summaryCalls = mockApi.dashboardSummary.mock.calls.length;
    const bucketCalls = mockApi.rollingSpendingBucketPerformance.mock.calls.length;
    const recurringCalls = mockApi.recurringBillTimeline.mock.calls.length;
    mockApi.dashboardSummary.mockResolvedValue(refreshedSummary);
    mockApi.recurringBillTimeline.mockResolvedValue(refreshedRecurring);

    await act(async () => {
      mockFocusCallback?.();
    });

    await waitFor(() => expect(view.getByText('Updated rent')).toBeTruthy());
    expect(view.queryByText('Electricity')).toBeNull();
    expect(view.getByText('Insurance')).toBeTruthy();
    expect(view.queryByText('Internet')).toBeNull();
    const metrics = within(view.getByTestId('home-active-metrics'));
    expect(metrics.getByText('4')).toBeTruthy();
    expect(metrics.getByText('$321.00')).toBeTruthy();
    expect(metrics.getByText('7')).toBeTruthy();
    expect(metrics.getByText('6')).toBeTruthy();
    expect(mockApi.dashboardSummary).toHaveBeenCalledTimes(summaryCalls + 1);
    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(bucketCalls + 1);
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(recurringCalls + 1);
  });

  it('keeps cached summary visible when one focus refetch fails and updates successful groups', async () => {
    const updatedRolling = {
      ...rolling,
      summary: { budgetedMinor: 20000, netMinor: -500, spentMinor: 20500 },
    };
    const { view } = await renderHome();
    await waitFor(() => expect(view.getByText('Internet')).toBeTruthy());
    mockApi.dashboardSummary.mockRejectedValueOnce(new Error('summary offline'));
    mockApi.rollingSpendingBucketPerformance.mockResolvedValue(updatedRolling);
    mockApi.recurringBillTimeline.mockResolvedValue(refreshedRecurring);

    await act(async () => {
      mockFocusCallback?.();
    });

    expect(await view.findByText('Net over by $5.00')).toBeTruthy();
    expect(view.getByText('Insurance')).toBeTruthy();
    expect(view.getByText('Friday paycheck')).toBeTruthy();
    expect(view.getByText('Electricity')).toBeTruthy();
    expect(view.getAllByText('Showing saved data. Reconnect to refresh.').length).toBeGreaterThan(
      0,
    );
  });

  it('refetches all groups only when the app transitions back to active', async () => {
    const { view } = await renderHome();
    await waitFor(() => expect(view.getByText('Internet')).toBeTruthy());
    const summaryCalls = mockApi.dashboardSummary.mock.calls.length;
    const bucketCalls = mockApi.rollingSpendingBucketPerformance.mock.calls.length;
    const recurringCalls = mockApi.recurringBillTimeline.mock.calls.length;
    mockApi.dashboardSummary.mockResolvedValue(refreshedSummary);
    mockApi.recurringBillTimeline.mockResolvedValue(refreshedRecurring);

    await act(async () => {
      mockAppStateListener?.('background');
    });
    expect(mockApi.dashboardSummary).toHaveBeenCalledTimes(summaryCalls);
    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(bucketCalls);
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(recurringCalls);

    await act(async () => {
      mockAppStateListener?.('active');
    });

    await waitFor(() => expect(view.getByText('Updated rent')).toBeTruthy());
    expect(view.getByText('Insurance')).toBeTruthy();
    expect(mockApi.dashboardSummary).toHaveBeenCalledTimes(summaryCalls + 1);
    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(bucketCalls + 1);
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(recurringCalls + 1);
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

  it('coalesces overlapping focus and app-active refresh triggers', async () => {
    let resolveSummary: (value: DashboardSummary) => void = () => undefined;
    const pendingSummary = new Promise<DashboardSummary>((resolve) => {
      resolveSummary = resolve;
    });
    const { view } = await renderHome();
    await waitFor(() => expect(view.getByText('Internet')).toBeTruthy());
    const summaryCalls = mockApi.dashboardSummary.mock.calls.length;
    const bucketCalls = mockApi.rollingSpendingBucketPerformance.mock.calls.length;
    const recurringCalls = mockApi.recurringBillTimeline.mock.calls.length;
    mockApi.dashboardSummary.mockReturnValueOnce(pendingSummary);

    await act(async () => {
      mockFocusCallback?.();
    });
    await waitFor(() => expect(mockApi.dashboardSummary).toHaveBeenCalledTimes(summaryCalls + 1));

    await act(async () => {
      mockAppStateListener?.('background');
      mockAppStateListener?.('active');
    });

    expect(mockApi.dashboardSummary).toHaveBeenCalledTimes(summaryCalls + 1);
    expect(mockApi.rollingSpendingBucketPerformance).toHaveBeenCalledTimes(bucketCalls + 1);
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(recurringCalls + 1);

    await act(async () => {
      resolveSummary(refreshedSummary);
      await pendingSummary;
    });
    await waitFor(() => expect(view.getByText('Updated rent')).toBeTruthy());
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

  it('preserves an in-progress assignment and retries with the refreshed definition version', async () => {
    const selectedPaycheck = {
      allocatedMinor: 0,
      allocationPercent: 0,
      amountMinor: 50000,
      archivedAt: null,
      closedAt: null,
      completionPercent: 0,
      createdAt: '2026-07-01T00:00:00Z',
      entries: [],
      id: paycheckId,
      incomeDate: '2026-07-18',
      name: 'Friday paycheck',
      notPaidCount: 0,
      notPaidMinor: 0,
      notes: null,
      postedCount: 0,
      postedMinor: 0,
      processingCount: 0,
      processingMinor: 0,
      reopenedAt: null,
      requiresAttention: true,
      source: null,
      spendingBucketPerformance: null,
      state: 'ACTIVE' as const,
      templateSourceId: null,
      unallocatedMinor: 50000,
      updatedAt: '2026-07-01T00:00:00Z',
      version: 6,
    };
    const version4 = { ...recurring, items: [{ ...recurring.items[0], definitionVersion: 4 }] };
    const version5 = { ...recurring, items: [{ ...recurring.items[0], definitionVersion: 5 }] };
    mockApi.recurringBillTimeline.mockResolvedValueOnce(version4).mockResolvedValue(version5);
    mockApi.activePaychecks.mockResolvedValue({
      hasNext: false,
      items: [selectedPaycheck],
      page: 0,
      size: 100,
      totalItems: 1,
      totalPages: 1,
    });
    mockApi.paycheck.mockResolvedValue({ ...selectedPaycheck, version: 7 });
    mockApi.importRecurringBills
      .mockRejectedValueOnce(new Error('Paycheck changed. Refresh and try again.'))
      .mockResolvedValueOnce({
        ...selectedPaycheck,
        entries: [
          {
            accountName: null,
            amountMinor: 7000,
            createdAt: '2026-07-21T00:00:00Z',
            dueDate: '2026-07-22',
            entryType: 'BILL' as const,
            id: entryId,
            name: 'Internet',
            notes: null,
            overBudget: null,
            paybackId: null,
            paycheckId,
            payee: null,
            paymentMethod: 'AUTOPAY' as const,
            position: 0,
            remainingMinor: null,
            sinkingFundId: null,
            sourceExpenseLedgerId: null,
            sourceRecurringBillDefinitionId: definitionId,
            sourceRecurringOccurrenceDate: '2026-07-22',
            spentMinor: null,
            status: 'NOT_PAID' as const,
            targetDate: null,
            targetMinor: null,
            updatedAt: '2026-07-21T00:00:00Z',
            version: 0,
          },
        ],
      });
    const { view } = await renderHome();
    await fireEvent.press(await view.findByLabelText(/Internet.*Not added to a paycheck/));
    await fireEvent.press(await view.findByLabelText('Edit amount'));
    await fireEvent.changeText(view.getByLabelText('Amount for this paycheck'), '70.00');
    await fireEvent.press(view.getByLabelText('Update typical amount'));
    await fireEvent.press(
      view.getByLabelText(/Friday paycheck, income date.*currently unallocated/),
    );
    await fireEvent.press(view.getByLabelText('Confirm import'));

    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(2));
    expect(view.getByText('$70.00')).toBeTruthy();
    expect(
      view.getByLabelText(/Friday paycheck, income date.*currently unallocated/).props
        .accessibilityState.checked,
    ).toBe(true);
    await fireEvent.press(view.getByLabelText('Confirm import'));

    await waitFor(() => expect(mockApi.importRecurringBills).toHaveBeenCalledTimes(2));
    expect(mockApi.importRecurringBills.mock.calls[1]).toEqual([
      paycheckId,
      7,
      [
        {
          amountMinor: 7000,
          definitionId,
          definitionVersion: 5,
          occurrenceDate: '2026-07-22',
          updateTypicalAmount: true,
        },
      ],
    ]);
  });

  it('keeps assignment state while a normal Home refetch marks the occurrence assigned', async () => {
    const selectedPaycheck = {
      allocatedMinor: 0,
      allocationPercent: 0,
      amountMinor: 50000,
      archivedAt: null,
      closedAt: null,
      completionPercent: 0,
      createdAt: '2026-07-01T00:00:00Z',
      entries: [],
      id: paycheckId,
      incomeDate: '2026-07-18',
      name: 'Friday paycheck',
      notPaidCount: 0,
      notPaidMinor: 0,
      notes: null,
      postedCount: 0,
      postedMinor: 0,
      processingCount: 0,
      processingMinor: 0,
      reopenedAt: null,
      requiresAttention: true,
      source: null,
      spendingBucketPerformance: null,
      state: 'ACTIVE' as const,
      templateSourceId: null,
      unallocatedMinor: 50000,
      updatedAt: '2026-07-01T00:00:00Z',
      version: 6,
    };
    mockApi.activePaychecks.mockResolvedValue({
      hasNext: false,
      items: [selectedPaycheck],
      page: 0,
      size: 100,
      totalItems: 1,
      totalPages: 1,
    });
    const { view } = await renderHome();
    await fireEvent.press(await view.findByLabelText(/Internet.*Not added to a paycheck/));
    await fireEvent.press(await view.findByLabelText('Edit amount'));
    await fireEvent.changeText(view.getByLabelText('Amount for this paycheck'), '70.00');
    await fireEvent.press(view.getByLabelText('Update typical amount'));
    await fireEvent.press(
      view.getByLabelText(/Friday paycheck, income date.*currently unallocated/),
    );
    mockApi.recurringBillTimeline.mockResolvedValue({
      ...recurring,
      items: [
        {
          ...recurring.items[0],
          importCount: 1,
          imports: [
            {
              entryId,
              paycheckId,
              paycheckName: 'Friday paycheck',
              status: 'NOT_PAID' as const,
            },
          ],
        },
      ],
    });

    await act(async () => {
      mockFocusCallback?.();
    });

    expect(await view.findByLabelText('Open existing import')).toBeTruthy();
    expect(view.getByText('$70.00')).toBeTruthy();
    expect(
      view.getByLabelText(/Friday paycheck, income date.*currently unallocated/).props
        .accessibilityState.checked,
    ).toBe(true);
    expect(view.getByLabelText('Confirm import').props.accessibilityState.disabled).toBe(true);
    await fireEvent.press(view.getByLabelText('Confirm import'));
    expect(mockApi.importRecurringBills).not.toHaveBeenCalled();
    await fireEvent.press(view.getByLabelText('Open existing import'));
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/paychecks/[id]',
      params: { highlightEntryId: entryId, id: paycheckId },
    });
  });
});
