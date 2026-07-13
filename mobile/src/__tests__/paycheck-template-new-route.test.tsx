/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { PropsWithChildren, ReactNode } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { BudgetTemplate, Paycheck } from '@/api/contracts';

import NewPaycheckScreen from '../../app/paychecks/new';

const mockReplace = jest.fn();
const mockApi = {
  createPaycheck: jest.fn(),
  createPaycheckFromTemplate: jest.fn(),
  templates: jest.fn(),
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

jest.mock('@/components/segmented-control', () => {
  const React = require('react');
  const { Pressable, Text, View } = require('react-native');
  return {
    SegmentedControl: ({ label, onChange, options, value }: any) =>
      React.createElement(
        View,
        { accessibilityLabel: label },
        ...options.map((option: { label: string; value: string }) =>
          React.createElement(
            Pressable,
            {
              accessibilityLabel: option.label,
              accessibilityRole: 'radio',
              accessibilityState: { checked: option.value === value },
              key: option.value,
              onPress: () => onChange(option.value),
            },
            React.createElement(Text, null, option.label),
          ),
        ),
      ),
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

function renderRoute() {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { gcTime: Infinity, retry: false },
      queries: { gcTime: Infinity, retry: false },
    },
  });
  return render(<NewPaycheckScreen />, { wrapper: routeWrapper(queryClient) });
}

const createdPaycheck: Paycheck = {
  allocatedMinor: 120000,
  allocationPercent: 61,
  amountMinor: 197757,
  archivedAt: null,
  closedAt: null,
  completionPercent: 0,
  createdAt: '2026-07-10T12:00:00Z',
  entries: [],
  id: '11111111-1111-4111-8111-111111111120',
  incomeDate: '2026-07-17',
  name: 'Rent 1',
  notPaidCount: 2,
  notPaidMinor: 120000,
  notes: null,
  postedCount: 0,
  postedMinor: 0,
  processingCount: 0,
  processingMinor: 0,
  reopenedAt: null,
  requiresAttention: false,
  source: null,
  state: 'ACTIVE',
  templateSourceId: '11111111-1111-4111-8111-111111111200',
  unallocatedMinor: 77757,
  updatedAt: '2026-07-10T12:30:00Z',
  version: 0,
};

describe('new paycheck from template route', () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    jest.resetAllMocks();
    mockApi.createPaycheckFromTemplate.mockResolvedValue(createdPaycheck);
    mockApi.templates.mockResolvedValue({
      hasNext: false,
      items: [template()],
      page: 0,
      size: 1,
      totalItems: 1,
      totalPages: 1,
    });
  });

  it('selects a template, previews entries, submits once, and navigates', async () => {
    const view = await renderRoute();

    fireEvent.press(view.getByText('Use a template'));
    await waitFor(() => expect(view.getByText('Rent 1')).toBeTruthy());
    await waitFor(() => expect(view.getByText('Template preview')).toBeTruthy());
    expect(view.getByText('Bill | Manual Pay')).toBeTruthy();

    fireEvent.changeText(view.getByLabelText('Name'), 'Rent 1');
    fireEvent.changeText(view.getByLabelText('Exact paycheck amount'), '1977.57');
    fireEvent.changeText(view.getByLabelText('Income date'), '2026-07-17');
    fireEvent.press(view.getByLabelText('Create paycheck'));
    fireEvent.press(view.getByLabelText('Create paycheck'));

    await waitFor(() =>
      expect(mockApi.createPaycheckFromTemplate).toHaveBeenCalledWith(
        expect.objectContaining({
          amountMinor: 197757,
          incomeDate: '2026-07-17',
          name: 'Rent 1',
          templateId: '11111111-1111-4111-8111-111111111200',
        }),
      ),
    );
    expect(mockApi.createPaycheckFromTemplate).toHaveBeenCalledTimes(1);
    await waitFor(() =>
      expect(mockReplace).toHaveBeenCalledWith('/paychecks/11111111-1111-4111-8111-111111111120'),
    );
  });
});

function template(overrides: Partial<BudgetTemplate> = {}): BudgetTemplate {
  return {
    archived: false,
    archivedAt: null,
    createdAt: '2026-07-12T12:00:00Z',
    defaultTotalMinor: 120000,
    description: 'Repeat rent check',
    entries: [
      {
        accountName: null,
        createdAt: '2026-07-12T12:00:00Z',
        defaultAmountMinor: 110000,
        defaultDueOffsetDays: null,
        entryType: 'BILL',
        id: '11111111-1111-4111-8111-111111111201',
        name: 'Rent',
        notes: null,
        payee: null,
        paymentMethod: 'MANUAL',
        position: 0,
        targetDate: null,
        targetMinor: null,
        updatedAt: '2026-07-13T12:00:00Z',
        version: 1,
      },
      {
        accountName: null,
        createdAt: '2026-07-12T12:00:00Z',
        defaultAmountMinor: 10000,
        defaultDueOffsetDays: null,
        entryType: 'SPENDING_BUCKET',
        id: '11111111-1111-4111-8111-111111111202',
        name: 'Groceries',
        notes: null,
        payee: null,
        paymentMethod: null,
        position: 1,
        targetDate: null,
        targetMinor: null,
        updatedAt: '2026-07-13T12:00:00Z',
        version: 1,
      },
    ],
    entryCount: 2,
    id: '11111111-1111-4111-8111-111111111200',
    name: 'Rent 1',
    updatedAt: '2026-07-13T12:00:00Z',
    version: 7,
    ...overrides,
  };
}
