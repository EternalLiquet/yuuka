/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { SinkingFund, SinkingFundTransaction } from '@/api/contracts';

import SinkingFundDetailScreen from '../../app/sinking-funds/[id]';

const mockBack = jest.fn();
const mockApi = {
  archiveSinkingFund: jest.fn(),
  restoreSinkingFund: jest.fn(),
  reverseSinkingFundWithdrawal: jest.fn(),
  sinkingFund: jest.fn(),
  sinkingFundTransactions: jest.fn(),
  updateSinkingFund: jest.fn(),
  withdrawSinkingFund: jest.fn(),
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
    useLocalSearchParams: () => ({ id: fundId }),
    useRouter: () => ({ back: mockBack }),
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

const fundId = '11111111-1111-4111-8111-111111111300';

function wrapper(queryClient: QueryClient) {
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
  return render(<SinkingFundDetailScreen />, { wrapper: wrapper(queryClient) });
}

describe('Sinking Fund detail route', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.sinkingFund.mockResolvedValue(fund());
    mockApi.sinkingFundTransactions.mockResolvedValue(page([]));
  });

  it('loads older transactions beyond the first page', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) =>
      transaction(index + 1, `Withdrawal ${index + 1}`),
    );
    const older = transaction(101, 'Withdrawal 101');
    mockApi.sinkingFundTransactions.mockImplementation(async (_id: string, pageNumber: number) =>
      pageNumber === 0
        ? page(firstPage, { hasNext: true, totalItems: 101, totalPages: 2 })
        : page([older], { page: 1, totalItems: 101, totalPages: 2 }),
    );

    const view = await renderRoute();

    expect(await view.findByText('Showing 100 of 101 transactions')).toBeTruthy();
    fireEvent.press(view.getByLabelText('Load older transactions'));

    expect(await view.findByText('Withdrawal 101')).toBeTruthy();
    expect(view.getByText('Showing 101 of 101 transactions')).toBeTruthy();
    expect(mockApi.sinkingFundTransactions).toHaveBeenCalledWith(fundId, 0, 100);
    expect(mockApi.sinkingFundTransactions).toHaveBeenCalledWith(fundId, 1, 100);
  });

  it('requires confirmation before archiving a positive balance', async () => {
    mockApi.sinkingFund.mockResolvedValue(fund({ currentBalanceMinor: 5000 }));
    mockApi.archiveSinkingFund.mockResolvedValue(
      fund({ currentBalanceMinor: 5000, state: 'ARCHIVED' }),
    );
    const view = await renderRoute();

    await act(async () => fireEvent.press(await view.findByLabelText('Archive with balance')));
    await act(async () => fireEvent.press(await view.findByLabelText('Cancel')));
    expect(mockApi.archiveSinkingFund).not.toHaveBeenCalled();

    await act(async () => fireEvent.press(view.getByLabelText('Archive with balance')));
    await act(async () => fireEvent.press(await view.findByLabelText('Archive')));

    await waitFor(() => expect(mockApi.archiveSinkingFund).toHaveBeenCalledWith(fundId, 3, true));
  });

  it('keeps the archive confirmation open when the API rejects the change', async () => {
    mockApi.sinkingFund.mockResolvedValue(fund({ currentBalanceMinor: 5000 }));
    mockApi.archiveSinkingFund.mockRejectedValueOnce(new Error('Resource was updated.'));
    const view = await renderRoute();

    await act(async () => fireEvent.press(await view.findByLabelText('Archive with balance')));
    await act(async () => fireEvent.press(await view.findByLabelText('Archive')));

    expect(await view.findAllByText('Resource was updated.')).not.toHaveLength(0);
    expect(view.getByText('Cancel')).toBeTruthy();
  });

  it('archives a zero balance without the confirmation dialog', async () => {
    mockApi.sinkingFund.mockResolvedValue(fund({ currentBalanceMinor: 0 }));
    mockApi.archiveSinkingFund.mockResolvedValue(
      fund({ currentBalanceMinor: 0, state: 'ARCHIVED' }),
    );
    const view = await renderRoute();

    await act(async () => fireEvent.press(await view.findByLabelText('Archive')));

    await waitFor(() => expect(mockApi.archiveSinkingFund).toHaveBeenCalledWith(fundId, 3, false));
    expect(view.queryByText('Archive with balance')).toBeNull();
  });

  it('requires a reversal reason and sends the stored text once', async () => {
    const withdrawal = transaction(1, 'Washer repair');
    mockApi.sinkingFundTransactions.mockResolvedValue(page([withdrawal], { totalItems: 1 }));
    mockApi.reverseSinkingFundWithdrawal.mockResolvedValue(
      transaction(1, 'Washer repair', {
        reversalReason: 'Refund posted',
        reversedAt: '2026-07-19T12:00:00Z',
        version: 1,
      }),
    );
    const view = await renderRoute();

    await act(async () => fireEvent.press(await view.findByLabelText('Reverse withdrawal')));
    await act(async () => fireEvent.press(view.getAllByLabelText('Reverse withdrawal').at(-1)!));
    expect(await view.findByText('Enter a reversal reason.')).toBeTruthy();

    fireEvent.changeText(view.getByLabelText('Reason'), 'Refund posted');
    await act(async () => fireEvent.press(view.getAllByLabelText('Reverse withdrawal').at(-1)!));

    await waitFor(() =>
      expect(mockApi.reverseSinkingFundWithdrawal).toHaveBeenCalledWith(withdrawal.id, {
        reason: 'Refund posted',
        version: 0,
      }),
    );
    expect(mockApi.reverseSinkingFundWithdrawal).toHaveBeenCalledTimes(1);
  });

  it('displays stored reversal reasons', async () => {
    mockApi.sinkingFundTransactions.mockResolvedValue(
      page([
        transaction(1, 'Washer repair', {
          reversalReason: 'Refund posted',
          reversedAt: '2026-07-19T12:00:00Z',
        }),
      ]),
    );

    const view = await renderRoute();

    expect(await view.findByText(/Refund posted/)).toBeTruthy();
  });
});

function fund(overrides: Partial<SinkingFund> = {}): SinkingFund {
  return {
    archivedAt: null,
    createdAt: '2026-07-10T12:00:00Z',
    currentBalanceMinor: 25000,
    id: fundId,
    name: 'Emergency',
    notes: null,
    position: 0,
    progressPercent: null,
    remainingTargetMinor: null,
    state: 'ACTIVE',
    targetDate: null,
    targetMinor: null,
    transactionCount: 0,
    updatedAt: '2026-07-10T12:30:00Z',
    version: 3,
    ...overrides,
  };
}

function page(
  items: SinkingFundTransaction[],
  overrides: Partial<{
    hasNext: boolean;
    page: number;
    size: number;
    totalItems: number;
    totalPages: number;
  }> = {},
) {
  return {
    hasNext: false,
    items,
    page: 0,
    size: 100,
    totalItems: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    ...overrides,
  };
}

function transaction(
  index: number,
  reason: string,
  overrides: Partial<SinkingFundTransaction> = {},
): SinkingFundTransaction {
  const suffix = String(index).padStart(12, '0');
  return {
    amountMinor: 1000 + index,
    createdAt: '2026-07-10T12:00:00Z',
    effectiveDate: '2026-07-10',
    entryId: null,
    entryName: null,
    entryStatus: null,
    id: `11111111-1111-4111-8111-${suffix}`,
    notes: null,
    paycheckIncomeDate: null,
    paycheckName: null,
    reason,
    reversalReason: null,
    reversedAt: null,
    sinkingFundId: fundId,
    transactionType: 'WITHDRAWAL',
    updatedAt: '2026-07-10T12:30:00Z',
    version: 0,
    ...overrides,
  };
}
