/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
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

async function renderRoute() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  queryClients.push(queryClient);
  const view = await render(<EntrySearchScreen />, { wrapper: routeWrapper(queryClient) });
  return Object.assign(view, {
    queryClient,
  });
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
    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), '$13.99');
    });
    await settleDebounce();

    await waitFor(() =>
      expect(mockApi.searchEntries).toHaveBeenLastCalledWith({
        amountMinor: 1399,
        page: 0,
        query: undefined,
        scope: 'HISTORY',
      }),
    );

    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), 'streaming');
    });
    await settleDebounce();

    expect(await view.findByText('Netflix')).toBeTruthy();
    await act(async () => {
      fireEvent.press(await view.findByLabelText('Load more'));
    });

    expect(await view.findByText('Hulu')).toBeTruthy();
    await act(async () => {
      fireEvent.press(view.getByLabelText('Open Hulu in July Paycheck'));
    });

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

  it('hides successful results immediately when the input is cleared', async () => {
    const first = result({ entryName: 'Netflix' });
    mockApi.searchEntries.mockResolvedValueOnce(page([first]));
    const view = await renderRoute();

    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), 'Netflix');
    });
    await settleDebounce();

    expect(await view.findByLabelText('Open Netflix in July Paycheck')).toBeTruthy();
    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), '');
    });

    await waitFor(() => expect(view.getByText('Start a search')).toBeTruthy());
    await waitFor(() => expect(view.getByText('0 results')).toBeTruthy());
    await waitFor(() => expect(view.queryByLabelText('Open Netflix in July Paycheck')).toBeNull());
  });

  it('hides successful results immediately when the current amount is invalid', async () => {
    const first = result({ amountMinor: 1399, entryName: 'Netflix' });
    mockApi.searchEntries.mockResolvedValueOnce(page([first]));
    const view = await renderRoute();

    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), '$13.99');
    });
    await settleDebounce();

    expect(await view.findByLabelText('Open Netflix in July Paycheck')).toBeTruthy();
    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), '1.234');
    });

    await waitFor(() => expect(view.getByText('Check the amount')).toBeTruthy());
    await waitFor(() => expect(view.getByText('0 results')).toBeTruthy());
    await waitFor(() => expect(view.queryByLabelText('Open Netflix in July Paycheck')).toBeNull());
  });

  it('hides name results when Amount mode makes the current text invalid', async () => {
    const first = result({ entryName: 'Netflix' });
    mockApi.searchEntries.mockResolvedValueOnce(page([first]));
    const view = await renderRoute();

    await act(async () => {
      fireEvent.press(view.getByTestId('segmented-Search mode-NAME'));
    });
    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), 'Netflix');
    });
    await settleDebounce();

    expect(await view.findByLabelText('Open Netflix in July Paycheck')).toBeTruthy();
    await act(async () => {
      fireEvent.press(view.getByTestId('segmented-Search mode-AMOUNT'));
    });

    await waitFor(() => expect(view.getByText('Check the amount')).toBeTruthy());
    await waitFor(() => expect(view.getByText('0 results')).toBeTruthy());
    await waitFor(() => expect(view.queryByLabelText('Open Netflix in July Paycheck')).toBeNull());
  });

  it('keeps previous rows during a valid-to-valid search transition', async () => {
    const huluSearch = deferred<Page<EntrySearchResult>>();
    mockApi.searchEntries
      .mockResolvedValueOnce(page([result({ entryName: 'Netflix' })]))
      .mockReturnValueOnce(huluSearch.promise);
    const view = await renderRoute();

    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), 'Netflix');
    });
    await settleDebounce();

    expect(await view.findByLabelText('Open Netflix in July Paycheck')).toBeTruthy();
    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), 'Hulu');
    });
    await settleDebounce();

    await waitFor(() =>
      expect(mockApi.searchEntries).toHaveBeenLastCalledWith({
        amountMinor: undefined,
        page: 0,
        query: 'Hulu',
        scope: 'ALL',
      }),
    );
    expect(view.getByLabelText('Open Netflix in July Paycheck')).toBeTruthy();

    await act(async () => {
      huluSearch.resolve(page([result({ entryId: uuid('102'), entryName: 'Hulu' })]));
    });
    expect(await view.findByLabelText('Open Hulu in July Paycheck')).toBeTruthy();
  });

  it('shows stale server data only while the current criteria remain valid', async () => {
    mockApi.searchEntries.mockResolvedValueOnce(page([result({ entryName: 'Netflix' })]));
    const view = await renderRoute();

    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), 'Netflix');
    });
    await settleDebounce();

    expect(await view.findByLabelText('Open Netflix in July Paycheck')).toBeTruthy();
    await act(async () => {
      const searchQuery = view.queryClient
        .getQueryCache()
        .find({ queryKey: ['search', 'entries', 'ALL', 'Netflix', null] });
      searchQuery?.setState({ error: new Error('offline'), status: 'error' });
    });

    expect(await view.findByText('Showing saved data. Reconnect to refresh.')).toBeTruthy();
    expect(view.getByLabelText('Open Netflix in July Paycheck')).toBeTruthy();

    await act(async () => {
      fireEvent.changeText(view.getByPlaceholderText('Netflix or $13.99'), '');
    });

    await waitFor(() => expect(view.getByText('Start a search')).toBeTruthy());
    await waitFor(() => expect(view.getByText('0 results')).toBeTruthy());
    await waitFor(() =>
      expect(view.queryByText('Showing saved data. Reconnect to refresh.')).toBeNull(),
    );
    await waitFor(() => expect(view.queryByLabelText('Open Netflix in July Paycheck')).toBeNull());
  });
});

async function settleDebounce() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 350));
  });
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
}
