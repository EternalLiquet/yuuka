/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import NewTemplateScreen from '../../app/templates/new';

const mockReplace = jest.fn();
const mockApi = {
  createTemplate: jest.fn(),
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

function client() {
  return new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
}

describe('New template route', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    jest.resetAllMocks();
  });

  it('keeps entered values visible after create fails', async () => {
    const queryClient = client();
    mockApi.createTemplate.mockRejectedValue(new Error('Template name is invalid'));
    const view = await render(<NewTemplateScreen />, { wrapper: wrapper(queryClient) });

    fireEvent.changeText(view.getByLabelText('Name'), 'Bad Template');
    fireEvent.changeText(view.getByLabelText('Description (optional)'), 'Keep this');
    fireEvent.press(view.getByLabelText('Create template'));

    await waitFor(() => expect(view.getByText('Template name is invalid')).toBeTruthy());
    expect(view.getByLabelText('Name').props.value).toBe('Bad Template');
    expect(view.getByLabelText('Description (optional)').props.value).toBe('Keep this');
    queryClient.clear();
  });
});
