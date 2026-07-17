/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Entry, Paycheck } from '@/api/contracts';

import DuplicatePaycheckScreen from '../../app/paychecks/duplicate/[id]';

const mockReplace = jest.fn();
let mockParams: Record<string, string> = {};
const mockApi = {
  createPaycheckFromDraft: jest.fn(),
  paycheck: jest.fn(),
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
    useRouter: () => ({ replace: mockReplace }),
  };
});

jest.mock('react-native/Libraries/Modal/Modal', () => {
  const React = require('react');
  const { View } = require('react-native');
  function MockModal({ children, visible }: { children?: ReactNode; visible?: boolean }) {
    return visible ? React.createElement(View, null, children) : null;
  }
  return {
    __esModule: true,
    default: MockModal,
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

function createQueryClient() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false, staleTime: 30_000 },
    },
  });
  queryClients.push(queryClient);
  return queryClient;
}

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

async function renderRoute(queryClient = createQueryClient()) {
  return render(<DuplicatePaycheckScreen />, { wrapper: routeWrapper(queryClient) });
}

const createdPaycheck: Paycheck = {
  allocatedMinor: 119000,
  allocationPercent: 99,
  amountMinor: 120000,
  archivedAt: null,
  closedAt: null,
  completionPercent: 0,
  createdAt: '2026-07-16T12:00:00Z',
  entries: [],
  id: '11111111-1111-4111-8111-111111111199',
  incomeDate: '2026-07-16',
  name: 'Rent 1/2',
  notPaidCount: 3,
  notPaidMinor: 119000,
  notes: 'Original notes',
  postedCount: 0,
  postedMinor: 0,
  processingCount: 0,
  processingMinor: 0,
  reopenedAt: null,
  requiresAttention: true,
  source: 'Employer',
  spendingBucketPerformance: null,
  state: 'ACTIVE',
  templateSourceId: null,
  unallocatedMinor: 1000,
  updatedAt: '2026-07-16T12:00:00Z',
  version: 0,
};

describe('duplicate paycheck route', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockParams = { id: sourcePaycheck().id };
    mockApi.paycheck.mockResolvedValue(sourcePaycheck());
    mockApi.createPaycheckFromDraft.mockResolvedValue(createdPaycheck);
  });

  it('ignores stale detail-cache data and initializes from the fresh duplicate source', async () => {
    const sourceId = sourcePaycheck().id;
    const queryClient = createQueryClient();
    const stalePaycheck = sourcePaycheck({
      amountMinor: 50000,
      entries: [
        entry({
          amountMinor: 50000,
          dueDate: '2026-07-05',
          id: '11111111-1111-4111-8111-111111111201',
          name: 'Stale cached bill',
        }),
      ],
      name: 'Stale cached paycheck',
      source: 'Stale source',
    });
    const freshPaycheck = sourcePaycheck({
      amountMinor: 135000,
      entries: [
        entry({
          amountMinor: 85000,
          dueDate: '2026-08-04',
          id: '11111111-1111-4111-8111-111111111301',
          name: 'Fresh rent',
          paymentMethod: 'MANUAL',
          position: 0,
        }),
        entry({
          amountMinor: 25000,
          entryType: 'SPENDING_BUCKET',
          id: '11111111-1111-4111-8111-111111111302',
          name: 'Fresh groceries',
          paymentMethod: null,
          position: 1,
        }),
      ],
      incomeDate: '2026-08-01',
      name: 'Fresh authoritative paycheck',
      source: 'Fresh source',
    });
    queryClient.setQueryData(['paycheck', sourceId], stalePaycheck);
    queryClient.setQueryData(['paycheck', 'duplicate-source', sourceId], stalePaycheck);
    const sourceRequest = deferred<Paycheck>();
    mockApi.paycheck.mockReturnValue(sourceRequest.promise);

    const view = await renderRoute(queryClient);

    expect(view.getByLabelText('Loading paycheck...')).toBeTruthy();
    expect(view.queryByLabelText('Name')).toBeNull();
    expect(view.queryByText('Stale cached bill')).toBeNull();

    await act(async () => {
      sourceRequest.resolve(freshPaycheck);
      await sourceRequest.promise;
    });

    await waitFor(() =>
      expect(view.getByLabelText('Name').props.value).toBe('Fresh authoritative paycheck'),
    );
    expect(mockApi.paycheck).toHaveBeenCalledWith(sourceId);
    expect(view.getByLabelText('Exact paycheck amount').props.value).toBe('1350.00');

    expect(view.queryByText('Fresh rent')).toBeNull();
    expect(view.queryByText('Stale cached bill')).toBeNull();
    expect(mockApi.createPaycheckFromDraft).not.toHaveBeenCalled();
  });

  it('keeps loading until the authoritative duplicate-source request succeeds', async () => {
    const sourceId = sourcePaycheck().id;
    const queryClient = createQueryClient();
    const stalePaycheck = sourcePaycheck({
      entries: [entry({ name: 'Cached pending bill' })],
      name: 'Cached pending paycheck',
    });
    const freshPaycheck = sourcePaycheck({
      amountMinor: 140000,
      entries: [entry({ amountMinor: 140000, name: 'Fresh pending bill' })],
      name: 'Fresh pending paycheck',
    });
    const sourceRequest = deferred<Paycheck>();
    queryClient.setQueryData(['paycheck', sourceId], stalePaycheck);
    queryClient.setQueryData(['paycheck', 'duplicate-source', sourceId], stalePaycheck);
    mockApi.paycheck.mockReturnValue(sourceRequest.promise);

    const view = await renderRoute(queryClient);

    expect(view.getByLabelText('Loading paycheck...')).toBeTruthy();
    expect(view.queryByLabelText('Name')).toBeNull();
    expect(view.queryByText('Cached pending bill')).toBeNull();

    await act(async () => {
      sourceRequest.resolve(freshPaycheck);
      await sourceRequest.promise;
    });

    await waitFor(() =>
      expect(view.getByLabelText('Name').props.value).toBe('Fresh pending paycheck'),
    );
    expect(view.getByLabelText('Exact paycheck amount').props.value).toBe('1400.00');
    fireEvent(view.getByLabelText('Continue to entries'), 'press');
    expect(await view.findByText('Fresh pending bill')).toBeTruthy();
    expect(view.queryByText('Cached pending bill')).toBeNull();
  });

  it('shows a load error instead of using stale cache when the authoritative fetch fails', async () => {
    const sourceId = sourcePaycheck().id;
    const queryClient = createQueryClient();
    const stalePaycheck = sourcePaycheck({
      entries: [entry({ name: 'Cached failed bill' })],
      name: 'Cached failed paycheck',
    });
    const freshRetryPaycheck = sourcePaycheck({ name: 'Fresh retry paycheck' });
    queryClient.setQueryData(['paycheck', sourceId], stalePaycheck);
    queryClient.setQueryData(['paycheck', 'duplicate-source', sourceId], stalePaycheck);
    mockApi.paycheck
      .mockRejectedValueOnce(new Error('fresh source failed'))
      .mockResolvedValueOnce(freshRetryPaycheck);

    const view = await renderRoute(queryClient);

    await waitFor(() => expect(view.getByText('Could not load')).toBeTruthy());
    expect(view.getByText('fresh source failed')).toBeTruthy();
    expect(view.queryByLabelText('Name')).toBeNull();
    expect(view.queryByLabelText('Create paycheck')).toBeNull();
    expect(view.queryByText('Cached failed bill')).toBeNull();
    expect(mockApi.createPaycheckFromDraft).not.toHaveBeenCalled();

    fireEvent.press(view.getByLabelText('Retry'));

    await waitFor(() => expect(mockApi.paycheck).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(view.getByLabelText('Name').props.value).toBe('Fresh retry paycheck'),
    );
  });

  it('creates once from the fresh authoritative draft despite stale cache and repeated taps', async () => {
    const sourceId = sourcePaycheck().id;
    const queryClient = createQueryClient();
    const stalePaycheck = sourcePaycheck({
      amountMinor: 33300,
      entries: [
        entry({
          amountMinor: 33300,
          dueDate: '2026-07-05',
          id: '11111111-1111-4111-8111-111111111501',
          name: 'Stale submit bill',
        }),
      ],
      name: 'Stale submit paycheck',
      source: 'Stale submit source',
    });
    queryClient.setQueryData(['paycheck', sourceId], stalePaycheck);
    queryClient.setQueryData(['paycheck', 'duplicate-source', sourceId], stalePaycheck);
    mockApi.paycheck.mockResolvedValue(sourcePaycheck());
    const view = await renderRoute(queryClient);

    await waitFor(() => expect(view.getByLabelText('Name').props.value).toBe('Rent 1/2'));
    expect(view.getByLabelText('Exact paycheck amount').props.value).toBe('1200.00');
    fireEvent.changeText(view.getByLabelText('Income date'), '2026-07-16');
    fireEvent.press(view.getByText('Continue to entries'));
    await flushReactWork();

    expect(await view.findByText('Draft entries')).toBeTruthy();
    expect(view.getByText('1 Payback assignment was not copied.')).toBeTruthy();
    expect(view.getByText('1 LEFTOVER entry was excluded.')).toBeTruthy();
    expect(view.queryByText('Stale submit bill')).toBeNull();

    await act(async () => {
      queryClient.setQueriesData(
        { queryKey: ['paycheck', 'duplicate-source', sourceId] },
        sourcePaycheck({
          amountMinor: 999999,
          entries: [
            entry({
              amountMinor: 999999,
              id: '11111111-1111-4111-8111-111111111601',
              name: 'Later server entry',
            }),
          ],
          name: 'Later server paycheck',
          source: 'Later server source',
        }),
      );
    });
    expect(view.getByLabelText('Name').props.value).toBe('Rent 1/2');
    expect(view.getByText('Rent')).toBeTruthy();
    expect(view.queryByText('Later server entry')).toBeNull();

    fireEvent.press(view.getByLabelText('Create paycheck'));
    fireEvent.press(view.getByLabelText('Create paycheck'));
    await flushReactWork();

    await waitFor(() =>
      expect(mockApi.createPaycheckFromDraft).toHaveBeenCalledWith(
        expect.objectContaining({
          amountMinor: 120000,
          incomeDate: '2026-07-16',
          name: 'Rent 1/2',
          source: 'Employer',
        }),
      ),
    );
    expect(mockApi.createPaycheckFromDraft).toHaveBeenCalledTimes(1);
    expect(mockApi.createPaycheckFromDraft).toHaveBeenCalledWith(
      expect.objectContaining({
        entries: [
          expect.objectContaining({
            dueDate: '2026-07-22',
            name: 'Rent',
            paymentMethod: 'MANUAL',
          }),
          expect.objectContaining({
            name: 'Groceries',
            paymentMethod: null,
          }),
          expect.objectContaining({
            name: 'Insurance',
            targetDate: '2026-12-01',
            targetMinor: 120000,
          }),
        ],
      }),
    );
    expect(mockApi.createPaycheckFromDraft).not.toHaveBeenCalledWith(
      expect.objectContaining({
        entries: expect.arrayContaining([expect.objectContaining({ name: 'Stale submit bill' })]),
      }),
    );
    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith(`/paychecks/${createdPaycheck.id}`),
    );
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, reject, resolve };
}

async function flushReactWork() {
  await act(async () => {
    await Promise.resolve();
  });
}

function sourcePaycheck(overrides: Partial<Paycheck> = {}): Paycheck {
  const paycheck: Paycheck = {
    allocatedMinor: 120000,
    allocationPercent: 100,
    amountMinor: 120000,
    archivedAt: null,
    closedAt: '2026-07-10T12:00:00Z',
    completionPercent: 100,
    createdAt: '2026-07-02T12:00:00Z',
    entries: [
      entry({
        accountName: 'Checking',
        amountMinor: 94000,
        dueDate: '2026-07-08',
        id: '11111111-1111-4111-8111-111111111101',
        name: 'Rent',
        payee: 'Apartment',
        paymentMethod: 'MANUAL',
        position: 0,
      }),
      entry({
        amountMinor: 10000,
        entryType: 'SPENDING_BUCKET',
        id: '11111111-1111-4111-8111-111111111102',
        name: 'Groceries',
        paymentMethod: null,
        position: 1,
      }),
      entry({
        amountMinor: 15000,
        entryType: 'SINKING_FUND',
        id: '11111111-1111-4111-8111-111111111103',
        name: 'Insurance',
        paybackId: '11111111-1111-4111-8111-111111111777',
        paymentMethod: null,
        position: 2,
        targetDate: '2026-12-01',
        targetMinor: 120000,
      }),
      entry({
        amountMinor: 1000,
        id: '11111111-1111-4111-8111-111111111104',
        name: 'LEFTOVER',
        position: 3,
      }),
    ],
    id: '11111111-1111-4111-8111-111111111100',
    incomeDate: '2026-07-02',
    name: 'Rent 1/2',
    notPaidCount: 0,
    notPaidMinor: 0,
    notes: 'Original notes',
    postedCount: 4,
    postedMinor: 120000,
    processingCount: 0,
    processingMinor: 0,
    reopenedAt: null,
    requiresAttention: false,
    source: 'Employer',
    spendingBucketPerformance: null,
    state: 'CLOSED',
    templateSourceId: null,
    unallocatedMinor: 0,
    updatedAt: '2026-07-10T12:00:00Z',
    version: 4,
  };
  return { ...paycheck, ...overrides };
}

function entry(overrides: Partial<Entry>): Entry {
  return {
    accountName: null,
    amountMinor: 1000,
    createdAt: '2026-07-02T12:00:00Z',
    dueDate: null,
    entryType: 'BILL',
    id: '11111111-1111-4111-8111-111111111101',
    name: 'Entry',
    notes: null,
    overBudget: null,
    paybackId: null,
    payee: null,
    paycheckId: '11111111-1111-4111-8111-111111111100',
    paymentMethod: 'AUTOPAY',
    position: 0,
    remainingMinor: null,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    spentMinor: null,
    status: 'POSTED',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-07-10T12:00:00Z',
    version: 1,
    ...overrides,
  };
}
