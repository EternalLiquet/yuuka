/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
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

jest.mock('@/features/paychecks/entry-editor', () => {
  const React = require('react');
  const { Pressable, Text } = require('react-native');
  return {
    EntryEditor: ({
      onSubmit,
      visible,
    }: {
      onSubmit: (payload: unknown) => Promise<void>;
      visible: boolean;
    }) =>
      visible
        ? React.createElement(
            Pressable,
            {
              accessibilityLabel: 'Mock save entry',
              onPress: () =>
                onSubmit({
                  accountName: null,
                  amountMinor: 1000,
                  dueDate: null,
                  entryType: 'BILL',
                  name: 'Repayment',
                  notes: null,
                  paybackId: '11111111-1111-4111-8111-111111111188',
                  payee: null,
                  targetDate: null,
                  targetMinor: null,
                }),
            },
            React.createElement(Text, null, 'Mock save entry'),
          )
        : null,
  };
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

const paycheck: Paycheck = {
  allocatedMinor: 0,
  allocationPercent: 0,
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
  requiresAttention: false,
  source: null,
  state: 'ACTIVE',
  templateSourceId: null,
  unallocatedMinor: 20000,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 3,
};

describe('paycheck Payback cache invalidation', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockParams = { id: paycheck.id };
    mockApi.paycheck.mockResolvedValue(paycheck);
    mockApi.paybacks.mockResolvedValue({
      items: [],
      summary: {
        activeCount: 0,
        totalOriginalMinor: 0,
        totalRemainingMinor: 0,
        totalRepaidMinor: 0,
      },
    });
    mockApi.addEntry.mockResolvedValue({
      accountName: null,
      amountMinor: 1000,
      createdAt: '2026-07-10T12:00:00Z',
      dueDate: null,
      entryType: 'BILL',
      id: '11111111-1111-4111-8111-111111111111',
      name: 'Repayment',
      notes: null,
      overBudget: null,
      paybackId: '11111111-1111-4111-8111-111111111188',
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

  it('invalidates Payback list and detail prefixes after entry mutations', async () => {
    const invalidateSpy = jest.spyOn(QueryClient.prototype, 'invalidateQueries');
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    const view = await render(<PaycheckDetailScreen />, { wrapper: routeWrapper(queryClient) });

    fireEvent.press(await view.findByLabelText('Add entry'));
    fireEvent.press(await view.findByLabelText('Mock save entry'));

    await waitFor(() => expect(mockApi.addEntry).toHaveBeenCalled());
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['paybacks'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['payback'] });

    invalidateSpy.mockRestore();
    queryClient.clear();
  });
});
