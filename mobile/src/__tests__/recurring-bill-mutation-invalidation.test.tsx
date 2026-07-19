/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import type { RecurringBill } from '@/api/contracts';

import EditRecurringBillScreen from '../../app/recurring-bills/[id]/edit';
import NewRecurringBillScreen from '../../app/recurring-bills/new';

const definitionId = '11111111-1111-4111-8111-111111111111';
const mockBack = jest.fn();
const mockReplace = jest.fn();
const mockApi = {
  createRecurringBill: jest.fn(),
  recurringBill: jest.fn(),
  updateRecurringBill: jest.fn(),
};
const queryClients: QueryClient[] = [];

jest.mock('expo-router', () => {
  function Stack() {
    return null;
  }
  Stack.Screen = function Screen() {
    return null;
  };
  return {
    Stack,
    useLocalSearchParams: () => ({ id: definitionId }),
    useRouter: () => ({ back: mockBack, replace: mockReplace }),
  };
});

jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));

jest.mock('@/features/recurring-bills/recurring-bill-editor', () => {
  const React = require('react');
  const { Pressable, Text } = require('react-native');
  return {
    RecurringBillEditor: ({
      onSubmit,
      submitLabel,
    }: {
      onSubmit: (payload: Record<string, unknown>) => Promise<void>;
      submitLabel: string;
    }) =>
      React.createElement(
        Pressable,
        {
          accessibilityLabel: submitLabel,
          onPress: () => onSubmit(mockPayload),
        },
        React.createElement(Text, null, submitLabel),
      ),
  };
});

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD', theme: 'dark' } }),
}));

function client() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false, staleTime: Infinity },
    },
  });
  queryClients.push(queryClient);
  return queryClient;
}

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

describe('Recurring Bill mutation timeline invalidation', () => {
  afterEach(async () => {
    await cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.createRecurringBill.mockResolvedValue(definition);
    mockApi.recurringBill.mockResolvedValue(definition);
    mockApi.updateRecurringBill.mockResolvedValue(definition);
  });

  it('invalidates every cached recurring Bills timeline after creation', async () => {
    const queryClient = client();
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    const view = await render(<NewRecurringBillScreen />, { wrapper: wrapper(queryClient) });

    await fireEvent.press(view.getByLabelText('Create recurring Bill'));

    await waitFor(() => expect(mockApi.createRecurringBill).toHaveBeenCalledWith(mockPayload));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['recurring-bills'] }));
    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith(`/recurring-bills/${definitionId}`),
    );
  });

  it('invalidates every cached recurring Bills timeline after editing a definition', async () => {
    const queryClient = client();
    queryClient.setQueryData(['recurring-bill', definitionId], definition);
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    const view = await render(<EditRecurringBillScreen />, { wrapper: wrapper(queryClient) });
    expect(await view.findByLabelText('Save recurring Bill')).toBeTruthy();

    await fireEvent.press(view.getByLabelText('Save recurring Bill'));

    await waitFor(() =>
      expect(mockApi.updateRecurringBill).toHaveBeenCalledWith(definitionId, {
        ...mockPayload,
        version: 2,
      }),
    );
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['recurring-bills'] }));
    await waitFor(() => expect(mockBack).toHaveBeenCalledTimes(1));
  });
});

const mockPayload = {
  accountName: null,
  dueDay: 21,
  name: 'Electric',
  notes: null,
  payee: null,
  paymentMethod: 'AUTOPAY' as const,
  typicalAmountMinor: 1000,
};

const definition: RecurringBill = {
  ...mockPayload,
  active: true,
  createdAt: '2026-07-01T12:00:00Z',
  id: definitionId,
  recurrenceType: 'MONTHLY',
  updatedAt: '2026-07-02T12:00:00Z',
  version: 2,
};
