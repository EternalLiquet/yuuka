import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { BucketTransaction, Entry } from '@/api/contracts';
import { BucketTransactionSheet } from '@/features/paychecks/bucket-transaction-sheet';

const mockApi = {
  addBucketTransaction: jest.fn(),
  bucketTransactions: jest.fn(),
  deleteBucketTransaction: jest.fn(),
  updateBucketTransaction: jest.fn(),
};
const queryClients: QueryClient[] = [];

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

describe('bucket transaction sheet pagination', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
  });

  it('loads older purchases beyond the first page', async () => {
    const bucketEntry = entry();
    const firstPage = Array.from({ length: 100 }, (_, index) =>
      bucketTransaction(index + 1, `Purchase ${index + 1}`, bucketEntry.id),
    );
    const olderPurchase = bucketTransaction(101, 'Purchase 101', bucketEntry.id);
    mockApi.bucketTransactions.mockImplementation(
      async (_entryId: string, page: number, size: number) =>
        page === 0
          ? {
              hasNext: true,
              items: firstPage,
              page,
              size,
              totalItems: 101,
              totalPages: 2,
            }
          : {
              hasNext: false,
              items: [olderPurchase],
              page,
              size,
              totalItems: 101,
              totalPages: 2,
            },
    );
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    queryClients.push(queryClient);
    const view = await render(
      <BucketTransactionSheet
        entry={bucketEntry}
        onChanged={jest.fn().mockResolvedValue(undefined)}
        onClose={jest.fn()}
      />,
      { wrapper: wrapper(queryClient) },
    );

    expect(await view.findByText('Showing 100 of 101 purchases')).toBeTruthy();
    expect(view.getByLabelText('Load older purchases')).toBeTruthy();

    fireEvent.press(view.getByLabelText('Load older purchases'));

    expect(await view.findByText(/Purchase 101/)).toBeTruthy();
    expect(view.getByText('Showing 101 of 101 purchases')).toBeTruthy();
    expect(mockApi.bucketTransactions).toHaveBeenCalledWith(bucketEntry.id, 0, 100);
    expect(mockApi.bucketTransactions).toHaveBeenCalledWith(bucketEntry.id, 1, 100);
  });
});

function entry(): Entry {
  return {
    accountName: null,
    amountMinor: 10000,
    createdAt: '2026-07-10T12:00:00Z',
    dueDate: null,
    entryType: 'SPENDING_BUCKET',
    paymentMethod: null,
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Work Food',
    notes: null,
    overBudget: false,
    paybackId: null,
    payee: null,
    paycheckId: '11111111-1111-4111-8111-111111111110',
    position: 0,
    remainingMinor: 7000,
    sourceRecurringBillDefinitionId: null,
    sourceRecurringOccurrenceDate: null,
    spentMinor: 3000,
    status: 'PROCESSING',
    targetDate: null,
    targetMinor: null,
    updatedAt: '2026-07-10T12:30:00Z',
    version: 0,
  };
}

function bucketTransaction(index: number, description: string, entryId: string): BucketTransaction {
  const suffix = String(index).padStart(12, '0');
  return {
    amountMinor: 100 + index,
    createdAt: '2026-07-10T12:00:00Z',
    description,
    effectiveDate: '2026-07-10',
    entryId,
    id: `11111111-1111-4111-8111-${suffix}`,
    notes: null,
    updatedAt: '2026-07-10T12:30:00Z',
    version: 0,
  };
}
