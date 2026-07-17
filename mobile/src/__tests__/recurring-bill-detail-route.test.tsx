import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';
import { Alert } from 'react-native';

import type { RecurringBill } from '@/api/contracts';

import RecurringBillDetailScreen from '../../app/recurring-bills/[id]';

const recurringBillId = '11111111-1111-4111-8111-111111111111';
const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockApi = {
  activateRecurringBill: jest.fn(),
  deactivateRecurringBill: jest.fn(),
  deleteRecurringBill: jest.fn(),
  recurringBill: jest.fn(),
};

jest.mock('expo-router', () => {
  function Stack() {
    return null;
  }
  Stack.Screen = function Screen() {
    return null;
  };
  return {
    Stack,
    useLocalSearchParams: () => ({ id: recurringBillId }),
    useRouter: () => ({ push: mockPush, replace: mockReplace }),
  };
});

jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD', theme: 'dark' } }),
}));

function client() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false, staleTime: Infinity },
    },
  });
}

function wrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

describe('Recurring Bill detail route deletion', () => {
  afterEach(async () => {
    await cleanup();
    jest.restoreAllMocks();
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.recurringBill.mockResolvedValue(definition);
    mockApi.deleteRecurringBill.mockResolvedValue(undefined);
  });

  it('cancels safely, preserves failed deletes, and sends one confirmed request', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(jest.fn());
    const queryClient = client();
    queryClient.setQueryData(['recurring-bill', recurringBillId], definition);
    const view = await render(<RecurringBillDetailScreen />, { wrapper: wrapper(queryClient) });
    expect(await view.findByText('Electric')).toBeTruthy();

    fireEvent.press(view.getByLabelText('Delete recurring Bill'));
    expect(alert).toHaveBeenCalledWith(
      'Delete "Electric"?',
      expect.stringContaining('Existing imported paycheck entries remain unchanged.'),
      expect.any(Array),
    );
    const actions = alert.mock.calls[0][2] as { onPress?: () => void; text: string }[];
    actions.find((action) => action.text === 'Cancel')?.onPress?.();

    expect(mockApi.deleteRecurringBill).not.toHaveBeenCalled();
    expect(mockReplace).not.toHaveBeenCalled();

    mockApi.deleteRecurringBill.mockRejectedValue(new Error('Deletion unavailable'));
    fireEvent.press(view.getByLabelText('Delete recurring Bill'));
    const retryActions = alert.mock.calls[1][2] as { onPress?: () => void; text: string }[];
    await act(async () => {
      retryActions.find((action) => action.text === 'Delete')?.onPress?.();
    });

    expect(await view.findByText('Deletion unavailable')).toBeTruthy();
    expect(view.getByText('Due-day rule')).toBeTruthy();
    expect(mockApi.deleteRecurringBill).toHaveBeenCalledTimes(1);
    expect(mockReplace).not.toHaveBeenCalled();

    mockApi.deleteRecurringBill.mockClear().mockResolvedValue(undefined);
    fireEvent.press(view.getByLabelText('Delete recurring Bill'));
    const successActions = alert.mock.calls[2][2] as { onPress?: () => void; text: string }[];
    await act(async () => {
      successActions.find((action) => action.text === 'Delete')?.onPress?.();
      successActions.find((action) => action.text === 'Delete')?.onPress?.();
    });

    await waitFor(() => expect(mockApi.deleteRecurringBill).toHaveBeenCalledTimes(1));
    expect(mockApi.deleteRecurringBill).toHaveBeenCalledWith(recurringBillId, 2);
    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/recurring-bills/manage'));
    await waitFor(() =>
      expect(view.getByLabelText('Delete recurring Bill').props.accessibilityState.busy).toBe(
        false,
      ),
    );
  });
});

const definition: RecurringBill = {
  accountName: 'Checking',
  active: true,
  createdAt: '2026-07-01T12:00:00Z',
  dueDay: 21,
  id: recurringBillId,
  name: 'Electric',
  notes: 'Variable charge',
  payee: 'Power Co',
  paymentMethod: 'AUTOPAY',
  recurrenceType: 'MONTHLY',
  typicalAmountMinor: 12000,
  updatedAt: '2026-07-02T12:00:00Z',
  version: 2,
};
