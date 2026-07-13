import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Payback } from '@/api/contracts';

import PaybacksScreen from '../../app/(tabs)/paybacks';

const mockPush = jest.fn();
const mockApi = {
  paybacks: jest.fn(),
  reorderPaybacks: jest.fn(),
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

describe('Paybacks tab', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.paybacks.mockResolvedValue({
      items: [
        payback({ id: '11111111-1111-4111-8111-111111111101', name: 'Personal', position: 0 }),
        payback({ id: '11111111-1111-4111-8111-111111111102', name: 'Car repair', position: 1 }),
        payback({
          id: '11111111-1111-4111-8111-111111111103',
          name: 'Old loan',
          position: 2,
          state: 'PAID_OFF',
        }),
      ],
      summary: {
        activeCount: 2,
        totalOriginalMinor: 30000,
        totalRemainingMinor: 20000,
        totalRepaidMinor: 10000,
      },
    });
    mockApi.reorderPaybacks.mockResolvedValue({
      items: [],
      summary: {
        activeCount: 0,
        totalOriginalMinor: 0,
        totalRemainingMinor: 0,
        totalRepaidMinor: 0,
      },
    });
  });

  it('moves Paybacks using the persisted custom order contract', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    const view = await render(<PaybacksScreen />, { wrapper: wrapper(queryClient) });

    await waitFor(() => expect(view.getByText('Personal')).toBeTruthy());
    await fireEvent.press(view.getByLabelText('Move Car repair up'));

    await waitFor(() => expect(mockApi.reorderPaybacks).toHaveBeenCalled());
    expect(mockApi.reorderPaybacks).toHaveBeenCalledWith([
      '11111111-1111-4111-8111-111111111102',
      '11111111-1111-4111-8111-111111111101',
      '11111111-1111-4111-8111-111111111103',
    ]);
    queryClient.clear();
  });
});

function payback(overrides: Partial<Payback> = {}): Payback {
  return {
    borrowedDate: '2026-07-12',
    createdAt: '2026-07-12T12:00:00Z',
    id: '11111111-1111-4111-8111-111111111101',
    name: 'Personal',
    notes: null,
    openingRemainingAmountMinor: 10000,
    originalAmountMinor: 10000,
    position: 0,
    progressPercent: 0,
    remainingMinor: 10000,
    repaidMinor: 0,
    repaymentCount: 0,
    source: null,
    state: 'ACTIVE',
    updatedAt: '2026-07-12T12:30:00Z',
    version: 0,
    ...overrides,
  };
}
