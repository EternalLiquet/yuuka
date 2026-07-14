/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { Paycheck } from '@/api/contracts';

import NewPaycheckScreen from '../../app/paychecks/new';

const mockReplace = jest.fn();

const mockApi = {
  createPaycheck: jest.fn(),
  createPaycheckFromTemplate: jest.fn(),
  templates: jest.fn(),
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
    useRouter: () => ({ replace: mockReplace }),
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
  queryClients.push(queryClient);
  return render(<NewPaycheckScreen />, { wrapper: routeWrapper(queryClient) });
}

const paycheck: Paycheck = {
  allocatedMinor: 0,
  allocationPercent: 0,
  amountMinor: 197757,
  archivedAt: null,
  closedAt: null,
  completionPercent: 0,
  createdAt: '2026-07-10T12:00:00Z',
  entries: [],
  id: '11111111-1111-4111-8111-111111111110',
  incomeDate: '2026-07-17',
  name: 'Free Paycheck',
  notPaidCount: 0,
  notPaidMinor: 0,
  notes: null,
  postedCount: 0,
  postedMinor: 0,
  processingCount: 0,
  processingMinor: 0,
  reopenedAt: null,
  requiresAttention: false,
  spendingBucketPerformance: null,
  source: null,
  state: 'ACTIVE',
  templateSourceId: null,
  unallocatedMinor: 197757,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 0,
};

describe('new paycheck route regressions', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.createPaycheck.mockResolvedValue(paycheck);
    mockApi.createPaycheckFromTemplate.mockResolvedValue({
      ...paycheck,
      id: '11111111-1111-4111-8111-111111111120',
      templateSourceId: '11111111-1111-4111-8111-111111111200',
    });
    mockApi.templates.mockResolvedValue({
      hasNext: false,
      items: [],
      page: 0,
      size: 0,
      totalItems: 0,
      totalPages: 0,
    });
  });

  it('creates a scratch paycheck and navigates to detail without crashing', async () => {
    const view = await renderRoute();

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
