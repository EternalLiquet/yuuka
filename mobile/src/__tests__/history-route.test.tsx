import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Page, Paycheck } from '@/api/contracts';

import HistoryScreen from '../../app/(tabs)/history';

jest.mock('react-native/Libraries/Components/RefreshControl/RefreshControl', () => ({
  __esModule: true,
  default: 'RefreshControl',
}));

const mockPush = jest.fn();
const mockApi = {
  historyPaychecks: jest.fn(),
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

const unfilteredQuery = '&oldestFirst=false';

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

function client() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false, staleTime: Infinity },
    },
  });
}

async function renderRoute(
  initialItems: Paycheck[] = [paycheck({ id: uuid('100'), name: 'July Paycheck' })],
) {
  const queryClient = client();
  queryClient.setQueryData(['paychecks', 'history', unfilteredQuery], page(initialItems));
  const view = await render(<HistoryScreen />, { wrapper: wrapper(queryClient) });
  return Object.assign(view, { queryClient });
}

describe('History tab search', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date('2026-07-15T12:00:00Z'));
    jest.resetAllMocks();
    mockApi.historyPaychecks.mockResolvedValue(page([paycheck({ id: uuid('101') })]));
  });

  afterEach(() => {
    cleanup();
    jest.useRealTimers();
  });

  it('updates the field immediately while running only the final rapid search after debounce', async () => {
    const view = await renderRoute();
    const search = () => view.getByPlaceholderText('Name or source');

    await act(async () => {
      fireEvent.changeText(search(), 'N');
    });
    expect(search().props.value).toBe('N');
    expect(mockApi.historyPaychecks).not.toHaveBeenCalled();

    await advance(100);
    await act(async () => {
      fireEvent.changeText(search(), 'Ne');
    });
    expect(search().props.value).toBe('Ne');

    await advance(100);
    await act(async () => {
      fireEvent.changeText(search(), 'Net');
    });
    expect(search().props.value).toBe('Net');

    await advance(299);
    expect(mockApi.historyPaychecks).not.toHaveBeenCalled();

    await advance(1);
    expect(mockApi.historyPaychecks).toHaveBeenCalledTimes(1);
    expect(mockApi.historyPaychecks).toHaveBeenLastCalledWith('&search=Net&oldestFirst=false');
  });

  it('keeps the input and existing results usable while a debounced search is fetching', async () => {
    const pendingSearch = deferred<Page<Paycheck>>();
    mockApi.historyPaychecks.mockReturnValueOnce(pendingSearch.promise);
    const view = await renderRoute([paycheck({ id: uuid('102'), name: 'Existing History' })]);
    const search = () => view.getByPlaceholderText('Name or source');

    await act(async () => {
      fireEvent.changeText(search(), 'Utility');
    });
    await advance(300);

    expect(mockApi.historyPaychecks).toHaveBeenLastCalledWith('&search=Utility&oldestFirst=false');
    expect(search().props.value).toBe('Utility');
    expect(search().props.editable).not.toBe(false);
    expect(view.getByText('Existing History')).toBeTruthy();
    expect(view.queryByText('Loading history...')).toBeNull();

    await act(async () => {
      fireEvent.changeText(search(), 'Utility bill');
    });
    expect(search().props.value).toBe('Utility bill');
    expect(view.getByText('Existing History')).toBeTruthy();

    await act(async () => {
      pendingSearch.resolve(page([paycheck({ id: uuid('103'), name: 'Utility Paycheck' })]));
      await pendingSearch.promise;
    });
  });

  it('clearing the field returns to the unfiltered history query', async () => {
    const view = await renderRoute([paycheck({ id: uuid('104'), name: 'All History' })]);
    const search = () => view.getByPlaceholderText('Name or source');

    mockApi.historyPaychecks.mockResolvedValueOnce(
      page([paycheck({ id: uuid('105'), name: 'Filtered History' })]),
    );
    await act(async () => {
      fireEvent.changeText(search(), 'Filtered');
    });
    await advance(300);
    await flushPromises();
    await waitFor(() => expect(view.getByText('Filtered History')).toBeTruthy());

    view.queryClient.removeQueries({ queryKey: ['paychecks', 'history', unfilteredQuery] });
    mockApi.historyPaychecks.mockResolvedValueOnce(
      page([paycheck({ id: uuid('106'), name: 'All History' })]),
    );
    await act(async () => {
      fireEvent.changeText(search(), '');
    });
    expect(search().props.value).toBe('');
    await advance(300);
    await flushPromises();

    expect(mockApi.historyPaychecks).toHaveBeenLastCalledWith(unfilteredQuery);
    await waitFor(() => expect(view.getByText('All History')).toBeTruthy());
  });

  it('shows results for the latest debounced term when older requests resolve later', async () => {
    const netflixSearch = deferred<Page<Paycheck>>();
    const huluSearch = deferred<Page<Paycheck>>();
    mockApi.historyPaychecks
      .mockReturnValueOnce(netflixSearch.promise)
      .mockReturnValueOnce(huluSearch.promise);
    const view = await renderRoute([paycheck({ id: uuid('107'), name: 'All History' })]);
    const search = () => view.getByPlaceholderText('Name or source');

    await act(async () => {
      fireEvent.changeText(search(), 'Netflix');
    });
    await advance(300);
    expect(mockApi.historyPaychecks).toHaveBeenLastCalledWith('&search=Netflix&oldestFirst=false');

    await act(async () => {
      fireEvent.changeText(search(), 'Hulu');
    });
    await advance(300);
    expect(mockApi.historyPaychecks).toHaveBeenLastCalledWith('&search=Hulu&oldestFirst=false');

    await act(async () => {
      huluSearch.resolve(page([paycheck({ id: uuid('108'), name: 'Hulu History' })]));
      await huluSearch.promise;
    });
    await waitFor(() => expect(view.getByText('Hulu History')).toBeTruthy());

    await act(async () => {
      netflixSearch.resolve(page([paycheck({ id: uuid('109'), name: 'Netflix History' })]));
      await netflixSearch.promise;
    });

    await waitFor(() => expect(view.getByText('Hulu History')).toBeTruthy());
    expect(view.queryByText('Netflix History')).toBeNull();
  });
});

async function advance(ms: number) {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
  await flushPromises();
}

async function flushPromises() {
  await act(async () => {
    await Promise.resolve();
  });
}

function page(items: Paycheck[]): Page<Paycheck> {
  return {
    hasNext: false,
    items,
    page: 0,
    size: 100,
    totalItems: items.length,
    totalPages: items.length ? 1 : 0,
  };
}

function paycheck(overrides: Partial<Paycheck> = {}): Paycheck {
  return {
    allocatedMinor: 5000,
    allocationPercent: 100,
    amountMinor: 5000,
    archivedAt: null,
    closedAt: '2026-07-14T12:00:00Z',
    completionPercent: 100,
    createdAt: '2026-07-10T12:00:00Z',
    entries: [],
    id: '11111111-1111-4111-8111-111111111110',
    incomeDate: '2026-07-12',
    name: 'History Paycheck',
    notPaidCount: 0,
    notPaidMinor: 0,
    notes: null,
    postedCount: 1,
    postedMinor: 5000,
    processingCount: 0,
    processingMinor: 0,
    reopenedAt: null,
    requiresAttention: false,
    source: 'Work',
    spendingBucketPerformance: null,
    state: 'CLOSED',
    templateSourceId: null,
    unallocatedMinor: 0,
    updatedAt: '2026-07-14T12:30:00Z',
    version: 1,
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
