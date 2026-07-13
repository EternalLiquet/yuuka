import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren } from 'react';

import NewPaybackScreen from '../../app/paybacks/new';

const mockBack = jest.fn();
const mockReplace = jest.fn();
const mockApi = {
  createPayback: jest.fn(),
};

jest.mock('expo-router', () => ({
  useRouter: () => ({ back: mockBack, replace: mockReplace }),
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

function wrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  };
}

describe('new Payback route', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.createPayback.mockResolvedValue({
      id: '11111111-1111-4111-8111-111111111111',
      name: 'Personal loan',
    });
  });

  it('replaces to the created detail route without also going back', async () => {
    const view = await render(<NewPaybackScreen />, { wrapper: wrapper() });

    fireEvent.changeText(view.getByLabelText('Name'), 'Personal loan');
    fireEvent.changeText(view.getByLabelText('Original amount owed'), '123.45');
    await waitFor(() =>
      expect(view.getByLabelText('Balance when tracking began').props.value).toBe('123.45'),
    );
    fireEvent.press(view.getByLabelText('Create Payback'));

    await waitFor(() => expect(mockApi.createPayback).toHaveBeenCalled());
    expect(mockApi.createPayback.mock.calls[0][0]).toEqual(
      expect.objectContaining({
        name: 'Personal loan',
        originalAmountMinor: 12345,
        openingRemainingAmountMinor: 12345,
      }),
    );
    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith('/paybacks/11111111-1111-4111-8111-111111111111'),
    );
    expect(mockBack).not.toHaveBeenCalled();
  });
});
