/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, within } from '@testing-library/react-native';
import type { PropsWithChildren, ReactElement, ReactNode } from 'react';
import { StyleSheet } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import type { SinkingFund } from '@/api/contracts';

import SinkingFundsScreen from '../../app/(tabs)/planned-savings';

const mockApi = {
  reorderSinkingFunds: jest.fn(),
  sinkingFunds: jest.fn(),
};
const mockPush = jest.fn();
const queryClients: QueryClient[] = [];
let latestListProps: Record<string, unknown> = {};

jest.mock('react-native/Libraries/Lists/FlatList', () => {
  const React = require('react');
  const { View } = require('react-native');
  function MockFlatList(props: {
    contentContainerStyle?: unknown;
    data?: unknown[];
    keyExtractor: (item: unknown, index: number) => string;
    ListEmptyComponent?: ReactNode;
    ListHeaderComponent?: ReactNode;
    renderItem: (info: { index: number; item: unknown }) => ReactElement;
  }) {
    const { data = [], keyExtractor, ListEmptyComponent, ListHeaderComponent, renderItem } = props;
    latestListProps = props as Record<string, unknown>;
    return React.createElement(
      View,
      null,
      ListHeaderComponent,
      data.length === 0 ? ListEmptyComponent : null,
      ...data.map((item, index) =>
        React.cloneElement(renderItem({ index, item }), {
          key: keyExtractor(item, index),
        }),
      ),
    );
  }
  return {
    __esModule: true,
    default: MockFlatList,
  };
});

jest.mock('expo-router', () => {
  function Stack() {
    return null;
  }
  Stack.Screen = function Screen() {
    return null;
  };
  return { Stack, useRouter: () => ({ push: mockPush }) };
});

jest.mock('@/api/use-yuuka-api', () => ({
  useYuukaApi: () => mockApi,
}));

jest.mock('@/hooks/use-minimum-visible-duration', () => ({
  useMinimumVisibleDuration: (visible: boolean) => visible,
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
        <SafeAreaProvider>
          <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
        </SafeAreaProvider>
      </GestureHandlerRootView>
    );
  };
}

describe('Planned Savings route', () => {
  afterEach(() => {
    cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    latestListProps = {};
  });

  it('keeps the title block vertical and creates Planned Savings from a safe-area FAB', async () => {
    mockApi.sinkingFunds.mockResolvedValue({
      items: [fund(1)],
      summary: { activeCount: 1, archivedCount: 0, totalActiveBalanceMinor: 2500 },
    });
    const queryClient = new QueryClient({
      defaultOptions: {
        mutations: { gcTime: Infinity, retry: false },
        queries: { gcTime: Infinity, retry: false },
      },
    });
    queryClients.push(queryClient);
    const view = await render(<SinkingFundsScreen />, { wrapper: wrapper(queryClient) });

    expect(await view.findByText('Vacation')).toBeTruthy();
    const header = view.getByTestId('planned-savings-list-header');
    expect(within(header).queryByLabelText('New Planned Savings')).toBeNull();
    expect(StyleSheet.flatten(view.getByTestId('planned-savings-title-block').props.style)).toEqual(
      { gap: 3 },
    );

    const create = view.getByTestId('planned-savings-floating-create');
    expect(create.props.accessibilityLabel).toBe('New Planned Savings');
    expect(create.props.accessibilityRole).toBe('button');
    const createStyle = StyleSheet.flatten(create.props.style) as {
      bottom?: number;
      height?: number;
      position?: string;
      right?: number;
      width?: number;
    };
    expect(createStyle.position).toBe('absolute');
    expect(createStyle.bottom).toBe(18);
    expect(createStyle.right).toBe(18);
    expect(createStyle.height).toBe(58);
    expect(createStyle.width).toBe(58);

    const contentStyle = StyleSheet.flatten(latestListProps.contentContainerStyle) as {
      paddingBottom?: number;
    };
    expect(contentStyle.paddingBottom).toBeGreaterThanOrEqual(100);

    await act(async () => fireEvent.press(create));
    expect(mockPush).toHaveBeenCalledWith('/sinking-funds/new');
  });
});

function fund(index: number): SinkingFund {
  const suffix = String(index).padStart(12, '0');
  return {
    archivedAt: null,
    createdAt: '2026-07-18T12:00:00Z',
    currentBalanceMinor: 2500,
    id: `11111111-1111-4111-8111-${suffix}`,
    name: 'Vacation',
    notes: null,
    position: index,
    progressPercent: null,
    remainingTargetMinor: null,
    state: 'ACTIVE',
    targetDate: null,
    targetMinor: null,
    transactionCount: 0,
    updatedAt: '2026-07-18T12:00:00Z',
    version: 0,
  };
}
