/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactElement, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Entry, Paycheck } from '@/api/contracts';

import PaycheckDetailScreen from '../../app/paychecks/[id]';

const mockReplace = jest.fn();
const mockPush = jest.fn();
const mockScrollToIndex = jest.fn();
let mockParams: Record<string, string> = {};
let mockOnScrollBeginDrag: (() => void) | null = null;
let mockOnScrollToIndexFailed:
  | ((info: {
      averageItemLength: number;
      highestMeasuredFrameIndex: number;
      index: number;
    }) => void)
  | null = null;

const mockApi = {
  addBucketTransaction: jest.fn(),
  addEntry: jest.fn(),
  allocateLeftover: jest.fn(),
  archivePaycheck: jest.fn(),
  bucketTransactions: jest.fn(),
  changeStatus: jest.fn(),
  closePaycheck: jest.fn(),
  deleteBucketTransaction: jest.fn(),
  deleteEntry: jest.fn(),
  paybacks: jest.fn(),
  paycheck: jest.fn(),
  reopenPaycheck: jest.fn(),
  reorderEntries: jest.fn(),
  statusHistory: jest.fn(),
  updateBucketTransaction: jest.fn(),
  updateEntry: jest.fn(),
  updatePaycheck: jest.fn(),
};
const queryClients: QueryClient[] = [];

jest.mock('expo-router', () => {
  const React = require('react');
  const { View } = require('react-native');
  function Stack({ children }: { children?: ReactNode }) {
    return React.createElement(View, null, children);
  }
  Stack.Screen = function Screen() {
    return null;
  };
  return {
    Stack,
    useLocalSearchParams: () => mockParams,
    useRouter: () => ({ push: mockPush, replace: mockReplace }),
  };
});

jest.mock('react-native-draggable-flatlist', () => {
  const React = require('react');
  const { FlatList } = require('react-native');
  const DraggableFlatList = React.forwardRef(function DraggableFlatList(
    {
      renderItem,
      ...props
    }: {
      onScrollBeginDrag?: () => void;
      onScrollToIndexFailed?: (info: {
        averageItemLength: number;
        highestMeasuredFrameIndex: number;
        index: number;
      }) => void;
      renderItem: (params: { drag: () => void; index: number; item: Entry }) => ReactNode;
    },
    ref: unknown,
  ) {
    React.useImperativeHandle(ref, () => ({
      scrollToIndex: mockScrollToIndex,
    }));
    mockOnScrollBeginDrag = props.onScrollBeginDrag ?? null;
    mockOnScrollToIndexFailed = props.onScrollToIndexFailed ?? null;
    return React.createElement(FlatList, {
      ...props,
      renderItem: (params: { index: number; item: Entry }) =>
        renderItem({ ...params, drag: jest.fn() }),
    });
  });
  return { __esModule: true, default: DraggableFlatList };
});

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

function routeWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return (
      <GestureHandlerRootView style={{ flex: 1 }}>
        <SafeAreaProvider
          initialMetrics={{
            frame: { height: 844, width: 390, x: 0, y: 0 },
            insets: { bottom: 0, left: 0, right: 0, top: 0 },
          }}
        >
          <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
        </SafeAreaProvider>
      </GestureHandlerRootView>
    );
  };
}

async function renderRoute(screen: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  queryClients.push(queryClient);
  return render(screen, { wrapper: routeWrapper(queryClient) });
}

const entries: Entry[] = [
  entry({
    entryType: 'BILL',
    name: 'Electricity',
    paymentMethod: 'MANUAL',
    position: 0,
    status: 'NOT_PAID',
  }),
  entry({
    entryType: 'SPENDING_BUCKET',
    name: 'Work Food',
    overBudget: false,
    position: 1,
    remainingMinor: 7000,
    spentMinor: 3000,
    status: 'PROCESSING',
  }),
  entry({ entryType: 'SINKING_FUND', name: 'Tires', position: 2, status: 'POSTED' }),
];

const paycheck: Paycheck = {
  allocatedMinor: 20000,
  allocationPercent: 100,
  amountMinor: 20000,
  archivedAt: null,
  closedAt: null,
  completionPercent: 25,
  createdAt: '2026-07-10T12:00:00Z',
  entries,
  id: '11111111-1111-4111-8111-111111111110',
  incomeDate: '2026-07-17',
  name: 'UTILITIES 1/2',
  notPaidCount: 1,
  notPaidMinor: 5000,
  notes: null,
  postedCount: 1,
  postedMinor: 5000,
  processingCount: 1,
  processingMinor: 10000,
  reopenedAt: null,
  requiresAttention: true,
  source: null,
  state: 'ACTIVE',
  templateSourceId: null,
  unallocatedMinor: 0,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 3,
};

describe('paycheck route regressions', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockParams = { id: paycheck.id };
    mockOnScrollBeginDrag = null;
    mockOnScrollToIndexFailed = null;
    mockApi.paybacks.mockResolvedValue({
      items: [],
      summary: {
        activeCount: 0,
        totalOriginalMinor: 0,
        totalRemainingMinor: 0,
        totalRepaidMinor: 0,
      },
    });
    mockApi.paycheck.mockResolvedValue(paycheck);
    mockApi.allocateLeftover.mockResolvedValue(
      entry({
        amountMinor: 2500,
        entryType: 'BILL',
        name: 'LEFTOVER',
        position: 3,
        status: 'NOT_PAID',
      }),
    );
    mockApi.bucketTransactions.mockResolvedValue({
      hasNext: false,
      items: [
        {
          amountMinor: 1250,
          createdAt: '2026-07-10T12:00:00Z',
          description: 'Cafe',
          effectiveDate: '2026-07-10',
          entryId: entries[1].id,
          id: '11111111-1111-4111-8111-111111111199',
          notes: 'Lunch receipt',
          updatedAt: '2026-07-10T12:30:00Z',
          version: 0,
        },
      ],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    });
  });

  it('scrolls to a highlighted entry after loading the paycheck', async () => {
    mockParams = { highlightEntryId: entries[2].id, id: paycheck.id };

    const view = await renderRoute(<PaycheckDetailScreen />);

    expect(await view.findByText('Tires', {}, { timeout: 5000 })).toBeTruthy();
    await waitFor(() =>
      expect(mockScrollToIndex).toHaveBeenCalledWith({
        animated: true,
        index: 2,
        viewPosition: 0.35,
      }),
    );
  }, 10000);

  it('retries a failed highlight scroll after list measurement catches up', async () => {
    mockParams = { highlightEntryId: entries[2].id, id: paycheck.id };

    const view = await renderRoute(<PaycheckDetailScreen />);

    expect(await view.findByText('Tires', {}, { timeout: 5000 })).toBeTruthy();
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));

    await act(async () => {
      mockOnScrollToIndexFailed?.({
        averageItemLength: 60,
        highestMeasuredFrameIndex: 0,
        index: 2,
      });
      await waitForHighlightRetry();
    });

    expect(mockScrollToIndex).toHaveBeenCalledTimes(2);
    expect(mockScrollToIndex).toHaveBeenLastCalledWith({
      animated: true,
      index: 2,
      viewPosition: 0.35,
    });
  }, 10000);

  it('cancels highlight retry after user scroll interaction and resets for a new highlight', async () => {
    mockParams = { highlightEntryId: entries[0].id, id: paycheck.id };

    const view = await renderRoute(<PaycheckDetailScreen />);

    expect(await view.findByText('Electricity', {}, { timeout: 5000 })).toBeTruthy();
    await waitFor(() =>
      expect(mockScrollToIndex).toHaveBeenLastCalledWith({
        animated: true,
        index: 0,
        viewPosition: 0.35,
      }),
    );

    await act(async () => {
      mockOnScrollToIndexFailed?.({
        averageItemLength: 60,
        highestMeasuredFrameIndex: 0,
        index: 0,
      });
      mockOnScrollBeginDrag?.();
      await waitForHighlightRetry();
    });

    expect(mockScrollToIndex).toHaveBeenCalledTimes(1);

    mockParams = { highlightEntryId: entries[2].id, id: paycheck.id };
    view.rerender(<PaycheckDetailScreen />);

    await waitFor(() =>
      expect(mockScrollToIndex).toHaveBeenLastCalledWith({
        animated: true,
        index: 2,
        viewPosition: 0.35,
      }),
    );
  }, 10000);

  it('opens an existing paycheck detail with draggable entries without crashing', async () => {
    const view = await renderRoute(<PaycheckDetailScreen />);

    await waitFor(() => expect(mockApi.paycheck).toHaveBeenCalled());
    expect(await view.findByText('UTILITIES 1/2')).toBeTruthy();
    expect(view.getByText('Electricity')).toBeTruthy();
    expect(view.getByText('Work Food')).toBeTruthy();
    expect(view.getByText('Tires')).toBeTruthy();
    expect(view.getByLabelText('Status: Not Paid')).toBeTruthy();
    expect(view.getByLabelText('Status: Processing')).toBeTruthy();
    expect(view.getByLabelText('Status: Posted')).toBeTruthy();
    expect(view.queryByText('Add LEFTOVER')).toBeNull();
  });

  it('filters Manual Pay Bills together with Not Paid status', async () => {
    const view = await renderRoute(<PaycheckDetailScreen />);

    expect(await view.findByText('Electricity')).toBeTruthy();
    await act(async () => {
      fireEvent.press(view.getByTestId('segmented-Payment method filter-MANUAL'));
      fireEvent.press(view.getByTestId('segmented-Status filter-NOT_PAID'));
    });

    expect(view.getByText('Electricity')).toBeTruthy();
    expect(view.getAllByText(/Manual Pay/).length).toBeGreaterThanOrEqual(1);
    expect(view.queryByText('Work Food')).toBeNull();
    expect(view.queryByText('Tires')).toBeNull();
  });

  it('allocates leftover as an exact bill entry', async () => {
    mockApi.paycheck.mockResolvedValue({
      ...paycheck,
      allocatedMinor: 17500,
      allocationPercent: 87.5,
      unallocatedMinor: 2500,
    });
    const view = await renderRoute(<PaycheckDetailScreen />);

    const button = await view.findByLabelText('Add LEFTOVER');
    fireEvent.press(button);

    await waitFor(() =>
      expect(mockApi.allocateLeftover).toHaveBeenCalledWith(paycheck.id, paycheck.version),
    );
    await waitFor(() => expect(mockApi.paycheck).toHaveBeenCalledTimes(2));
  });

  it('shows stale leftover conflicts and refreshes the paycheck', async () => {
    mockApi.paycheck.mockResolvedValue({
      ...paycheck,
      allocatedMinor: 17500,
      allocationPercent: 87.5,
      unallocatedMinor: 2500,
    });
    mockApi.allocateLeftover.mockRejectedValue(
      new Error('This paycheck changed. Refresh and try again.'),
    );
    const view = await renderRoute(<PaycheckDetailScreen />);

    fireEvent.press(await view.findByLabelText('Add LEFTOVER'));

    expect(await view.findByText('This paycheck changed. Refresh and try again.')).toBeTruthy();
    await waitFor(() => expect(mockApi.paycheck).toHaveBeenCalledTimes(2));
  });

  it('opens a bucket ledger with totals, purchases, notes, and validation', async () => {
    const view = await renderRoute(<PaycheckDetailScreen />);

    fireEvent.press(await view.findByLabelText('Add activity to Work Food'));

    expect(await view.findByText('Bucket ledger')).toBeTruthy();
    expect(view.getByText('Budgeted')).toBeTruthy();
    expect(view.getByText('Spent')).toBeTruthy();
    expect(view.getByText('Remaining')).toBeTruthy();
    expect(await view.findByText(/Cafe/)).toBeTruthy();
    expect(await view.findByText('Lunch receipt')).toBeTruthy();

    fireEvent.changeText(view.getAllByLabelText('Amount').at(-1)!, '-1.00');
    fireEvent.press(view.getByLabelText('Add purchase'));

    expect(
      await view.findByText('Enter a valid money amount with no more than two decimal places.'),
    ).toBeTruthy();
  });
});

function entry(overrides: Partial<Entry>): Entry {
  return {
    accountName: null,
    amountMinor: 5000,
    createdAt: '2026-07-10T12:00:00Z',
    dueDate: null,
    entryType: 'BILL',
    paymentMethod: overrides.entryType && overrides.entryType !== 'BILL' ? null : 'AUTOPAY',
    id: `11111111-1111-4111-8111-11111111111${overrides.position ?? 0}`,
    name: 'Entry',
    notes: null,
    overBudget: null,
    paybackId: null,
    payee: null,
    paycheckId: paycheckId(),
    position: 0,
    remainingMinor: null,
    spentMinor: null,
    status: 'NOT_PAID',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-07-10T12:30:00Z',
    version: 0,
    ...overrides,
  };
}

function paycheckId() {
  return '11111111-1111-4111-8111-111111111110';
}

async function waitForHighlightRetry() {
  await new Promise((resolve) => setTimeout(resolve, 100));
}
