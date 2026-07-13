/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { EntrySearchResult, Page, SearchScope } from '@/api/contracts';

import EntrySearchScreen from '../../app/search/entries';

const mockPush = jest.fn();
let mockParams: { scope?: SearchScope } = {};

const mockApi = {
  searchEntries: jest.fn(),
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
    useRouter: () => ({ push: mockPush }),
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

function renderRoute() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  queryClients.push(queryClient);
  return render(<EntrySearchScreen />, { wrapper: routeWrapper(queryClient) });
}

describe('entry search route', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockParams = {};
  });

  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  it('searches with initialized scope, appends pages, and opens a highlighted result', async () => {
    mockParams = { scope: 'HISTORY' };
    const first = result({ entryId: uuid('101'), entryName: 'Netflix' });
    const second = result({ entryId: uuid('102'), entryName: 'Hulu' });
    mockApi.searchEntries
      .mockResolvedValueOnce(page([result({ amountMinor: 1399 })]))
      .mockResolvedValueOnce(page([first], { hasNext: true, totalItems: 2, totalPages: 2 }))
      .mockResolvedValueOnce(page([second], { pageNumber: 1, totalItems: 2, totalPages: 2 }));
    const view = await renderRoute();

    expect(view.getByLabelText('History').props.accessibilityState.checked).toBe(true);
    fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), '$13.99');
    await settleDebounce();

    await waitFor(() =>
      expect(mockApi.searchEntries).toHaveBeenLastCalledWith({
        amountMinor: 1399,
        page: 0,
        query: undefined,
        scope: 'HISTORY',
      }),
    );

    fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), 'streaming');
    await settleDebounce();

    expect(await view.findByText('Netflix')).toBeTruthy();
    fireEvent.press(await view.findByLabelText('Load more'));

    expect(await view.findByText('Hulu')).toBeTruthy();
    fireEvent.press(view.getByLabelText('Open Hulu in July Paycheck'));

    expect(mockApi.searchEntries).toHaveBeenLastCalledWith({
      amountMinor: undefined,
      page: 1,
      query: 'streaming',
      scope: 'HISTORY',
    });
    expect(mockPush).toHaveBeenCalledWith({
      params: { highlightEntryId: second.entryId, id: second.paycheckId },
      pathname: '/paychecks/[id]',
    });
    expect(view.getByLabelText('Open Hulu in July Paycheck')).toBeTruthy();
  });
});

async function settleDebounce() {
  await new Promise((resolve) => setTimeout(resolve, 350));
}

function page(
  items: EntrySearchResult[],
  overrides: Partial<Page<EntrySearchResult> & { pageNumber: number }> = {},
): Page<EntrySearchResult> {
  const pageNumber = overrides.pageNumber ?? overrides.page ?? 0;
  return {
    hasNext: false,
    items,
    page: pageNumber,
    size: 20,
    totalItems: items.length,
    totalPages: items.length ? 1 : 0,
    ...overrides,
  };
}

function result(overrides: Partial<EntrySearchResult> = {}): EntrySearchResult {
  return {
    amountMinor: 1399,
    entryId: uuid('001'),
    entryName: 'Netflix',
    entryType: 'BILL',
    kind: 'PAYCHECK_ENTRY',
    paycheckContext: 'ACTIVE',
    paycheckId: uuid('900'),
    paycheckIncomeDate: '2026-07-17',
    paycheckName: 'July Paycheck',
    status: 'NOT_PAID',
    ...overrides,
  };
}

function uuid(suffix: string) {
  return `11111111-1111-4111-8111-111111111${suffix}`;
}
