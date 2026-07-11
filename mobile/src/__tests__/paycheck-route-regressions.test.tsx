/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactElement, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Entry, Paycheck } from '@/api/contracts';

import PaycheckDetailScreen from '../../app/paychecks/[id]';
import NewPaycheckScreen from '../../app/paychecks/new';

const mockReplace = jest.fn();
const mockPush = jest.fn();
let mockParams: Record<string, string> = {};

const mockApi = {
  addEntry: jest.fn(),
  archivePaycheck: jest.fn(),
  changeStatus: jest.fn(),
  closePaycheck: jest.fn(),
  createPaycheck: jest.fn(),
  deleteEntry: jest.fn(),
  paycheck: jest.fn(),
  reopenPaycheck: jest.fn(),
  reorderEntries: jest.fn(),
  statusHistory: jest.fn(),
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
  entry({ entryType: 'BILL', name: 'Electricity', position: 0, status: 'NOT_PAID' }),
  entry({ entryType: 'SPENDING_BUCKET', name: 'Work Food', position: 1, status: 'PROCESSING' }),
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
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.clearAllMocks();
    mockParams = { id: paycheck.id };
    mockApi.paycheck.mockResolvedValue(paycheck);
    mockApi.createPaycheck.mockResolvedValue({ ...paycheck, entries: [], id: paycheck.id });
  });

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
  });

  it('creates a scratch paycheck and navigates to detail without crashing', async () => {
    const view = await renderRoute(<NewPaycheckScreen />);

    fireEvent.changeText(view.getByLabelText('Name'), 'Free Paycheck');
    fireEvent.changeText(view.getByLabelText('Exact paycheck amount'), '1977.57');
    fireEvent.changeText(view.getByLabelText('Income date'), '2026-07-17');
    fireEvent.press(view.getByLabelText('Create paycheck'));

    await waitFor(() =>
      expect(mockApi.createPaycheck).toHaveBeenCalledWith(
        expect.objectContaining({
          amountMinor: 197757,
          incomeDate: '2026-07-17',
          name: 'Free Paycheck',
        }),
      ),
    );
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith(`/paychecks/${paycheck.id}`));
  });
});

function entry(overrides: Partial<Entry>): Entry {
  return {
    accountName: null,
    amountMinor: 5000,
    createdAt: '2026-07-10T12:00:00Z',
    dueDate: null,
    entryType: 'BILL',
    id: `11111111-1111-4111-8111-11111111111${overrides.position ?? 0}`,
    name: 'Entry',
    notes: null,
    overBudget: null,
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
