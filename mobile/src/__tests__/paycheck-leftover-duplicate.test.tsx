/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Entry, Paycheck } from '@/api/contracts';

import PaycheckDetailScreen from '../../app/paychecks/[id]';

let mockParams: Record<string, string> = {};

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
  };
});

jest.mock('react-native-draggable-flatlist', () => {
  const React = require('react');
  const { FlatList } = require('react-native');
  function DraggableFlatList({
    renderItem,
    ...props
  }: {
    renderItem: (params: { drag: () => void; index: number; item: Entry }) => ReactNode;
  }) {
    return React.createElement(FlatList, {
      ...props,
      renderItem: (params: { index: number; item: Entry }) =>
        renderItem({ ...params, drag: jest.fn() }),
    });
  }
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

async function renderRoute() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  queryClients.push(queryClient);
  return render(<PaycheckDetailScreen />, { wrapper: routeWrapper(queryClient) });
}

const paycheck: Paycheck = {
  allocatedMinor: 17500,
  allocationPercent: 87.5,
  amountMinor: 20000,
  archivedAt: null,
  closedAt: null,
  completionPercent: 0,
  createdAt: '2026-07-10T12:00:00Z',
  entries: [],
  id: '11111111-1111-4111-8111-111111111110',
  incomeDate: '2026-07-17',
  name: 'UTILITIES 1/2',
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
  state: 'ACTIVE',
  templateSourceId: null,
  unallocatedMinor: 2500,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 3,
};

describe('leftover duplicate tap protection', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockParams = { id: paycheck.id };
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
    mockApi.allocateLeftover.mockResolvedValue({
      accountName: null,
      amountMinor: 2500,
      createdAt: '2026-07-10T12:00:00Z',
      dueDate: null,
      entryType: 'BILL',
      paymentMethod: 'AUTOPAY',
      id: '11111111-1111-4111-8111-111111111120',
      name: 'LEFTOVER',
      notes: null,
      overBudget: null,
      paybackId: null,
      payee: null,
      paycheckId: paycheck.id,
      position: 0,
      remainingMinor: null,
      spentMinor: null,
      status: 'NOT_PAID',
      targetDate: null,
      targetMinor: null,
      updatedAt: '2026-07-10T12:30:00Z',
      version: 0,
    });
  });

  it('submits only once for repeated leftover taps', async () => {
    const view = await renderRoute();

    const button = await view.findByLabelText('Add LEFTOVER');
    fireEvent.press(button);
    fireEvent.press(button);

    await waitFor(() => expect(mockApi.allocateLeftover).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mockApi.paycheck).toHaveBeenCalledTimes(2));
  });
});
