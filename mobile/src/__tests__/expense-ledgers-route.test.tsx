/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render } from '@testing-library/react-native';
import type { PropsWithChildren, ReactElement, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { ExpenseLedger } from '@/api/contracts';

import ExpenseLedgersScreen from '../../app/(tabs)/expense-ledgers';

const mockApi = { expenseLedgers: jest.fn() };
const mockPush = jest.fn();
const queryClients: QueryClient[] = [];

jest.mock('react-native/Libraries/Lists/FlatList', () => {
  const React = require('react');
  const { View } = require('react-native');
  function MockFlatList({
    data = [],
    keyExtractor,
    ListEmptyComponent,
    ListFooterComponent,
    ListHeaderComponent,
    renderItem,
  }: {
    data?: unknown[];
    keyExtractor: (item: unknown, index: number) => string;
    ListEmptyComponent?: ReactNode;
    ListFooterComponent?: ReactNode;
    ListHeaderComponent?: ReactNode;
    renderItem: (info: { index: number; item: unknown }) => ReactElement;
  }) {
    return React.createElement(
      View,
      null,
      ListHeaderComponent,
      data.length === 0 ? ListEmptyComponent : null,
      ...data.map((item, index) =>
        React.cloneElement(renderItem({ index, item }), {
          key: keyExtractor(item, index),
        }),
      ),
      ListFooterComponent,
    );
  }
  return {
    __esModule: true,
    default: MockFlatList,
  };
});

jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
}));

jest.mock('@/api/use-yuuka-api', () => ({
  useYuukaApi: () => mockApi,
}));

jest.mock('@/hooks/use-minimum-visible-duration', () => ({
  useMinimumVisibleDuration: (visible: boolean) => visible,
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
      <GestureHandlerRootView style={{ flex: 1 }}>
        <SafeAreaProvider>
          <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
        </SafeAreaProvider>
      </GestureHandlerRootView>
    );
  };
}

describe('Expense List route pagination', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
  });

  it('loads and deduplicates later pages until ledger 101 is reachable', async () => {
    const firstPage = Array.from({ length: 50 }, (_, index) => ledger(index + 1));
    const secondPage = [
      ledger(50),
      ...Array.from({ length: 49 }, (_, index) => ledger(index + 51)),
    ];
    const thirdPage = [ledger(100), ledger(101)];
    mockApi.expenseLedgers.mockImplementation(
      async (state: string, pageNumber: number, size: number) => ({
        hasNext: pageNumber < 2,
        items: pageNumber === 0 ? firstPage : pageNumber === 1 ? secondPage : thirdPage,
        page: pageNumber,
        size,
        totalItems: 101,
        totalPages: 3,
      }),
    );
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    queryClients.push(queryClient);
    const view = await render(<ExpenseLedgersScreen />, { wrapper: wrapper(queryClient) });

    expect(await view.findByText('Showing 50 of 101 open expense lists')).toBeTruthy();
    await act(async () => fireEvent.press(view.getByText('Load older expense lists')));

    expect(await view.findByText('Showing 99 of 101 open expense lists')).toBeTruthy();
    await act(async () => fireEvent.press(view.getByText('Load older expense lists')));

    expect(await view.findByText('Ledger 101')).toBeTruthy();
    expect(view.getByText('Showing 101 of 101 open expense lists')).toBeTruthy();
    expect(mockApi.expenseLedgers).toHaveBeenNthCalledWith(1, 'OPEN', 0, 50);
    expect(mockApi.expenseLedgers).toHaveBeenNthCalledWith(2, 'OPEN', 1, 50);
    expect(mockApi.expenseLedgers).toHaveBeenNthCalledWith(3, 'OPEN', 2, 50);
  });
});

function ledger(index: number): ExpenseLedger {
  const suffix = String(index).padStart(12, '0');
  return {
    createdAt: '2026-07-18T12:00:00Z',
    finalizedAt: null,
    id: `11111111-1111-4111-8111-${suffix}`,
    itemCount: 1,
    items: [],
    latestExpenseDate: '2026-07-18',
    name: `Ledger ${index}`,
    notes: null,
    reopenedAt: null,
    settledAt: null,
    settlement: null,
    state: 'OPEN',
    totalMinor: index,
    updatedAt: '2026-07-18T12:00:00Z',
    version: 0,
  };
}
