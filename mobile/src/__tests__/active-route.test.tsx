import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import ActiveScreen from '../../app/(tabs)/active';

const mockPush = jest.fn();
const mockApi = { activePaychecks: jest.fn() };
const queryClients: QueryClient[] = [];

jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));
jest.mock('@/hooks/use-minimum-visible-duration', () => ({
  useMinimumVisibleDuration: () => false,
}));
jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({ settings: { currencyCode: 'USD', theme: 'dark' } }),
}));

function wrapper(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('Active route', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((client) => client.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.activePaychecks.mockResolvedValue({
      hasNext: false,
      items: [],
      page: 0,
      size: 100,
      totalItems: 0,
      totalPages: 0,
    });
  });

  it('keeps New Paycheck and Find Entry without the rolling bucket report', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { gcTime: Infinity, retry: false } },
    });
    queryClients.push(client);
    const view = await render(<ActiveScreen />, { wrapper: wrapper(client) });

    await waitFor(() => expect(mockApi.activePaychecks).toHaveBeenCalled());
    expect(view.getByLabelText('New paycheck')).toBeTruthy();
    expect(view.getByLabelText('Find entry')).toBeTruthy();
    expect(view.queryByText(/Spending Buckets · Last/)).toBeNull();

    fireEvent.press(view.getByLabelText('New paycheck'));
    fireEvent.press(view.getByLabelText('Find entry'));
    expect(mockPush).toHaveBeenNthCalledWith(1, '/paychecks/new');
    expect(mockPush).toHaveBeenNthCalledWith(2, '/search/entries?scope=ACTIVE');
  });
});
