/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
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
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockParams = { id: sourcePaycheck().id };
    mockApi.paycheck.mockResolvedValue(sourcePaycheck());
    mockApi.createPaycheckFromDraft.mockResolvedValue(createdPaycheck);
  });

  it('loads the source into a reviewed editable draft and creates from draft once', async () => {
    const view = await renderRoute();

    await waitFor(() => expect(view.getByLabelText('Name').props.value).toBe('Rent 1/2'));
    expect(view.getByLabelText('Exact paycheck amount').props.value).toBe('1200.00');
    fireEvent.changeText(view.getByLabelText('Income date'), '2026-07-16');
    fireEvent.press(view.getByLabelText('Continue to entries'));

    expect(await view.findByText('Draft entries')).toBeTruthy();
    expect(view.getByText('1 Payback assignment was not copied.')).toBeTruthy();
    expect(view.getByText('1 LEFTOVER entry was excluded.')).toBeTruthy();

    fireEvent.press(view.getByLabelText('Create paycheck'));
    fireEvent.press(view.getByLabelText('Create paycheck'));

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
    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith(`/paychecks/${createdPaycheck.id}`),
    );
  });
});

function sourcePaycheck(): Paycheck {
  return {
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
    spentMinor: null,
    status: 'POSTED',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-07-10T12:00:00Z',
    version: 1,
    ...overrides,
  };
}
