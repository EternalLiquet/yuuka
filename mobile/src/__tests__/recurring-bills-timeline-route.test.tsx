/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, waitFor, within } from '@testing-library/react-native';
import type { PropsWithChildren, ReactElement, ReactNode } from 'react';
import { StyleSheet } from 'react-native';

import type { RecurringBillOccurrence, RecurringBillTimeline } from '@/api/contracts';
import {
  initialTimelineRange,
  nextTimelineRange,
  previousTimelineRange,
} from '@/features/recurring-bills/timeline';

import RecurringBillsTimelineScreen from '../../app/recurring-bills';

const definitionId = '11111111-1111-4111-8111-111111111111';
const mockPush = jest.fn();
const mockScrollToIndex = jest.fn();
const mockScrollToOffset = jest.fn();
const mockApi = { recurringBillTimeline: jest.fn() };
let latestListProps: Record<string, unknown> = {};
const queryClients: QueryClient[] = [];

jest.mock('react-native/Libraries/Lists/FlatList', () => {
  const React = require('react');
  const { Pressable, View } = require('react-native');
  const MockFlatList = React.forwardRef(
    (
      props: {
        data?: unknown[];
        keyExtractor: (item: unknown, index: number) => string;
        ListFooterComponent?: ReactNode;
        ListHeaderComponent?: ReactNode;
        onViewableItemsChanged?: (info: { viewableItems: unknown[] }) => void;
        renderItem: (info: { index: number; item: unknown }) => ReactElement;
      },
      ref: React.Ref<unknown>,
    ) => {
      latestListProps = props as unknown as Record<string, unknown>;
      React.useImperativeHandle(ref, () => ({
        scrollToIndex: mockScrollToIndex,
        scrollToOffset: mockScrollToOffset,
      }));
      const data = props.data ?? [];
      return React.createElement(
        View,
        { testID: 'recurring-bills-timeline-list' },
        React.createElement(Pressable, {
          onPress: () => props.onViewableItemsChanged?.({ viewableItems: [] }),
          testID: 'mark-today-hidden',
        }),
        React.createElement(View, { testID: 'timeline-scroll-header' }, props.ListHeaderComponent),
        ...data.map((item, index) =>
          React.cloneElement(props.renderItem({ index, item }), {
            key: props.keyExtractor(item, index),
          }),
        ),
        props.ListFooterComponent,
      );
    },
  );
  MockFlatList.displayName = 'MockFlatList';
  return { __esModule: true, default: MockFlatList };
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

jest.mock('@/api/use-yuuka-api', () => ({ useYuukaApi: () => mockApi }));

jest.mock('@/settings/settings-provider', () => ({
  useSettings: () => ({
    settings: { currencyCode: 'USD', theme: 'dark', timezone: 'America/Indianapolis' },
  }),
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

describe('Recurring Bills timeline route', () => {
  afterEach(async () => {
    await cleanup();
    queryClients.forEach((queryClient) => queryClient.clear());
    queryClients.length = 0;
  });

  beforeEach(() => {
    jest.resetAllMocks();
    latestListProps = {};
    mockApi.recurringBillTimeline.mockImplementation(async (from: string, through: string) =>
      timeline(from, through, [occurrence(from)]),
    );
  });

  it('renders the accessible floating create action outside the scroll header with safe spacing', async () => {
    const view = await render(<RecurringBillsTimelineScreen />, { wrapper: wrapper(client()) });
    expect(await view.findByText('Electric')).toBeTruthy();
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));

    const header = view.getByTestId('timeline-scroll-header');
    expect(within(header).queryByLabelText('New recurring Bill')).toBeNull();
    expect(within(header).getByLabelText('Manage recurring Bills')).toBeTruthy();

    const create = view.getByLabelText('New recurring Bill');
    expect(create.props.accessibilityRole).toBe('button');
    await fireEvent.press(create);
    expect(mockPush).toHaveBeenCalledWith('/recurring-bills/new');

    const contentStyle = StyleSheet.flatten(latestListProps.contentContainerStyle) as {
      paddingBottom?: number;
    };
    expect(contentStyle.paddingBottom).toBeGreaterThanOrEqual(100);
  });

  it('edits the stable definition on long press or accessibility action, but not normal press', async () => {
    const view = await render(<RecurringBillsTimelineScreen />, { wrapper: wrapper(client()) });
    const card = await view.findByLabelText(/Electric, \$10\.00, Autopay/);
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));

    await fireEvent.press(card);
    expect(mockPush).not.toHaveBeenCalled();

    await fireEvent(card, 'longPress');
    expect(mockPush).toHaveBeenCalledTimes(1);
    expect(mockPush).toHaveBeenLastCalledWith(`/recurring-bills/${definitionId}/edit`);

    mockPush.mockClear();
    expect(card.props.accessibilityActions).toContainEqual({
      label: 'Edit recurring Bill',
      name: 'activate',
    });
    await fireEvent(card, 'accessibilityAction', { nativeEvent: { actionName: 'activate' } });
    expect(mockPush).toHaveBeenCalledTimes(1);
    expect(mockPush).toHaveBeenCalledWith(`/recurring-bills/${definitionId}/edit`);
  });

  it('restores the current range before jumping to Today when only distant pages are cached', async () => {
    const queryClient = client();
    const today = localToday();
    const distantRange = { from: '2025-01-01', through: '2025-01-31' };
    queryClient.setQueryData(['recurring-bills', 'timeline', today], {
      pageParams: [distantRange],
      pages: [timeline(distantRange.from, distantRange.through, [occurrence(distantRange.from)])],
    });
    const view = await render(<RecurringBillsTimelineScreen />, {
      wrapper: wrapper(queryClient),
    });
    expect(await view.findByText('Electric')).toBeTruthy();
    expect(mockApi.recurringBillTimeline).not.toHaveBeenCalled();

    await fireEvent.press(view.getByTestId('mark-today-hidden'));
    const jump = await view.findByLabelText('Jump to today');
    await fireEvent.press(jump);

    const currentRange = initialTimelineRange(today);
    await waitFor(() =>
      expect(mockApi.recurringBillTimeline).toHaveBeenCalledWith(
        currentRange.from,
        currentRange.through,
      ),
    );
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));
    await waitFor(() =>
      expect(view.getByLabelText('Jump to today').props.accessibilityState.busy).toBe(false),
    );
  });

  it('paginates, deduplicates, refreshes, and reports either edge without losing position', async () => {
    const view = await render(<RecurringBillsTimelineScreen />, { wrapper: wrapper(client()) });
    expect(await view.findByText('Electric')).toBeTruthy();
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));
    (latestListProps.onScrollBeginDrag as () => void)();
    const [initialFrom, initialThrough] = mockApi.recurringBillTimeline.mock.calls[0] as [
      string,
      string,
    ];

    await (latestListProps.onStartReached as () => Promise<void>)();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(2));
    const previous = previousTimelineRange(initialFrom);
    expect(mockApi.recurringBillTimeline).toHaveBeenNthCalledWith(
      2,
      previous.from,
      previous.through,
    );

    await (latestListProps.onStartReached as () => Promise<void>)();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(3));
    const earlier = previousTimelineRange(previous.from);
    expect(mockApi.recurringBillTimeline).toHaveBeenNthCalledWith(3, earlier.from, earlier.through);

    await (latestListProps.onEndReached as () => Promise<void>)();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(4));
    const next = nextTimelineRange(initialThrough);
    expect(mockApi.recurringBillTimeline).toHaveBeenNthCalledWith(4, next.from, next.through);

    await waitFor(() => expect(view.getAllByText('Electric')).toHaveLength(4));
    expect(view.getAllByLabelText(/^Today,/)).toHaveLength(1);
    expect(latestListProps.maintainVisibleContentPosition).toEqual({ minIndexForVisible: 0 });
    expect(mockScrollToIndex).toHaveBeenCalledTimes(1);

    mockApi.recurringBillTimeline.mockClear();
    const refreshControl = latestListProps.refreshControl as ReactElement<{
      onRefresh: () => Promise<unknown>;
    }>;
    await refreshControl.props.onRefresh();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(4));
    await waitFor(() =>
      expect(
        (latestListProps.refreshControl as ReactElement<{ refreshing: boolean }>).props.refreshing,
      ).toBe(false),
    );
    expect(view.getAllByText('Electric')).toHaveLength(4);
    expect(mockScrollToIndex).toHaveBeenCalledTimes(1);

    let resolveNext: (value: RecurringBillTimeline) => void = () => undefined;
    const pendingNext = new Promise<RecurringBillTimeline>((resolve) => {
      resolveNext = resolve;
    });
    mockApi.recurringBillTimeline.mockClear().mockImplementationOnce(() => pendingNext);
    const afterNext = nextTimelineRange(next.through);

    const first = (latestListProps.onEndReached as () => Promise<void>)();
    const repeated = (latestListProps.onEndReached as () => Promise<void>)();
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(1);
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledWith(afterNext.from, afterNext.through);
    expect(await view.findByText('Loading later timeline data…')).toBeTruthy();
    resolveNext(timeline(afterNext.from, afterNext.through, [occurrence(afterNext.from)]));
    await Promise.all([first, repeated]);
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(view.getAllByText('Electric')).toHaveLength(5));
    await waitFor(() => expect(view.queryByText('Loading later timeline data…')).toBeNull());

    mockApi.recurringBillTimeline.mockRejectedValueOnce(new Error('Earlier unavailable'));
    await (latestListProps.onStartReached as () => Promise<void>)();
    expect(await view.findByText('Earlier timeline data could not load.')).toBeTruthy();
    expect(view.getByLabelText('Retry earlier timeline data')).toBeTruthy();

    mockApi.recurringBillTimeline.mockRejectedValueOnce(new Error('Later unavailable'));
    await (latestListProps.onEndReached as () => Promise<void>)();
    expect(await view.findByText('Later timeline data could not load.')).toBeTruthy();
    expect(view.getByLabelText('Retry later timeline data')).toBeTruthy();
  });
});

function timeline(
  from: string,
  through: string,
  items: RecurringBillOccurrence[] = [],
): RecurringBillTimeline {
  return { from, items, through };
}

function occurrence(date: string): RecurringBillOccurrence {
  return {
    accountName: null,
    definitionId,
    definitionVersion: 2,
    importCount: 0,
    imports: [],
    name: 'Electric',
    notes: null,
    occurrenceDate: date,
    payee: null,
    paymentMethod: 'AUTOPAY',
    typicalAmountMinor: 1000,
  };
}

function localToday() {
  const value = new Date();
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
}
