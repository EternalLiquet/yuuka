/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { BudgetTemplate } from '@/api/contracts';

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

describe('New template route duplicate guard', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    jest.resetAllMocks();
  });

  it('guards rapid create taps and navigates once after success', async () => {
    const queryClient = client();
    let resolveTemplate: (template: BudgetTemplate) => void = () => undefined;
    mockApi.createTemplate.mockReturnValue(
      new Promise<BudgetTemplate>((resolve) => {
        resolveTemplate = resolve;
      }),
    );
    const view = await render(<NewTemplateScreen />, { wrapper: wrapper(queryClient) });

    fireEvent.changeText(view.getByLabelText('Name'), 'E2E Template');
    fireEvent.changeText(view.getByLabelText('Description (optional)'), 'Repeat plan');
    fireEvent.press(view.getByLabelText('Create template'));
    fireEvent.press(view.getByLabelText('Create template'));

    await waitFor(() => expect(mockApi.createTemplate).toHaveBeenCalledTimes(1));
    await act(async () => {
      resolveTemplate(template());
    });

    await waitFor(() => expect(mockReplace).toHaveBeenCalledWith('/templates/template-1'));
    expect(mockReplace).toHaveBeenCalledTimes(1);
    queryClient.clear();
  });
});

function template(): BudgetTemplate {
  return {
    archived: false,
    archivedAt: null,
    createdAt: '2026-07-12T12:00:00Z',
    defaultTotalMinor: 0,
    description: 'Repeat plan',
    entries: [],
    entryCount: 0,
    id: 'template-1',
    name: 'E2E Template',
    updatedAt: '2026-07-13T12:00:00Z',
    version: 0,
  };
}
