/* eslint-disable @typescript-eslint/no-require-imports */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, waitFor, within } from '@testing-library/react-native';
import type { PropsWithChildren, ReactElement, ReactNode } from 'react';
import { StyleSheet } from 'react-native';

import type { RecurringBillOccurrence, RecurringBillTimeline } from '@/api/contracts';
import {
  initialTimelineRange,
  type TimelineInfiniteData,
  type TimelineRange,
  nextTimelineRange,
  previousTimelineRange,
  timelineBounds,
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

  it('keeps the Today control compact, separate from the create FAB, and hides it when today is visible', async () => {
    const view = await render(<RecurringBillsTimelineScreen />, { wrapper: wrapper(client()) });
    expect(await view.findByText('Electric')).toBeTruthy();
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));

    expect(view.queryByLabelText('Jump to today')).toBeNull();

    await fireEvent.press(view.getByTestId('mark-today-hidden'));
    const jump = await view.findByTestId('recurring-bills-jump-today');
    expect(view.getByLabelText('Jump to today')).toBeTruthy();
    expect(view.getByText('Today')).toBeTruthy();

    const jumpStyle = StyleSheet.flatten(jump.props.style) as {
      maxWidth?: string;
      minHeight?: number;
      position?: string;
    };
    expect(jumpStyle.maxWidth).toBe('58%');
    expect(jumpStyle.minHeight).toBeGreaterThanOrEqual(44);
    expect(jumpStyle.position).toBeUndefined();

    const create = view.getByTestId('recurring-bills-floating-create');
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

    await act(async () => {
      (latestListProps.onViewableItemsChanged as (info: { viewableItems: unknown[] }) => void)({
        viewableItems: [{ item: { date: localToday(), items: [] } }],
      });
    });
    await waitFor(() => expect(view.queryByLabelText('Jump to today')).toBeNull());
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

  it('paginates, deduplicates, refreshes, and reports retryable edge errors without losing position', async () => {
    const view = await render(<RecurringBillsTimelineScreen />, { wrapper: wrapper(client()) });
    expect(await view.findByText('Electric')).toBeTruthy();
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));
    (latestListProps.onScrollBeginDrag as () => void)();
    const today = localToday();
    const [initialFrom, initialThrough] = mockApi.recurringBillTimeline.mock.calls[0] as [
      string,
      string,
    ];

    await (latestListProps.onStartReached as () => Promise<void>)();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(2));
    const previous = previousTimelineRange(initialFrom, today)!;
    expect(mockApi.recurringBillTimeline).toHaveBeenNthCalledWith(
      2,
      previous.from,
      previous.through,
    );

    await (latestListProps.onEndReached as () => Promise<void>)();
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(3));
    const next = nextTimelineRange(initialThrough, today)!;
    expect(mockApi.recurringBillTimeline).toHaveBeenNthCalledWith(3, next.from, next.through);

    await waitFor(() => expect(view.getAllByText('Electric')).toHaveLength(3));
    expect(view.getAllByLabelText(/^Today,/)).toHaveLength(1);
    expect(latestListProps.maintainVisibleContentPosition).toEqual({ minIndexForVisible: 0 });
    expect(mockScrollToIndex).toHaveBeenCalledTimes(1);

    mockApi.recurringBillTimeline.mockClear();
    const refreshControl = latestListProps.refreshControl as ReactElement<{
      onRefresh: () => Promise<unknown>;
    }>;
    await act(async () => {
      await refreshControl.props.onRefresh();
    });
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(3));
    await waitFor(() =>
      expect(
        (latestListProps.refreshControl as ReactElement<{ refreshing: boolean }>).props.refreshing,
      ).toBe(false),
    );
    expect(view.getAllByText('Electric')).toHaveLength(3);
    expect(mockScrollToIndex).toHaveBeenCalledTimes(1);

    mockApi.recurringBillTimeline.mockRejectedValueOnce(new Error('Later unavailable'));
    await (latestListProps.onEndReached as () => Promise<void>)();
    expect(await view.findByText('Later timeline data could not load.')).toBeTruthy();
    const laterRetry = view.getByLabelText('Retry later timeline data');
    expect(laterRetry).toBeTruthy();
    await fireEvent.press(laterRetry);
    await waitFor(() => expect(view.queryByText('Later timeline data could not load.')).toBeNull());
    await waitFor(() => expect(view.getAllByText('Electric')).toHaveLength(4));

    mockApi.recurringBillTimeline.mockRejectedValueOnce(new Error('Earlier unavailable'));
    await (latestListProps.onStartReached as () => Promise<void>)();
    expect(await view.findByText('Earlier timeline data could not load.')).toBeTruthy();
    expect(view.getByLabelText('Retry earlier timeline data')).toBeTruthy();
  });

  it('does not request or expose retry UI beyond the hard timeline boundaries', async () => {
    const queryClient = client();
    const today = localToday();
    const bounds = timelineBounds(today);
    queryClient.setQueryData(['recurring-bills', 'timeline', today], {
      pageParams: [bounds],
      pages: [timeline(bounds.from, bounds.through, [occurrence(today)])],
    });
    const view = await render(<RecurringBillsTimelineScreen />, {
      wrapper: wrapper(queryClient),
    });
    expect(await view.findByText('Electric')).toBeTruthy();
    await waitFor(() => expect(mockScrollToIndex).toHaveBeenCalledTimes(1));
    (latestListProps.onScrollBeginDrag as () => void)();

    await (latestListProps.onStartReached as () => Promise<void>)();
    await (latestListProps.onEndReached as () => Promise<void>)();
    expect(mockApi.recurringBillTimeline).not.toHaveBeenCalled();
    expect(view.queryByText('Earlier timeline data could not load.')).toBeNull();
    expect(view.queryByText('Later timeline data could not load.')).toBeNull();
    expect(view.queryByLabelText('Retry earlier timeline data')).toBeNull();
    expect(view.queryByLabelText('Retry later timeline data')).toBeNull();
  });

  it('keeps jump-to-today and pull-to-refresh bounded after browsing both edges', async () => {
    const queryClient = client();
    const today = localToday();
    const bounds = timelineBounds(today);
    const currentRange = initialTimelineRange(today);
    const lowerRange = { from: bounds.from, through: endOfMonth(bounds.from) };
    const upperRange = { from: startOfMonth(bounds.through), through: bounds.through };
    queryClient.setQueryData(['recurring-bills', 'timeline', today], {
      pageParams: [lowerRange, upperRange],
      pages: [
        timeline(lowerRange.from, lowerRange.through, [occurrence(lowerRange.from)]),
        timeline(upperRange.from, upperRange.through, [occurrence(upperRange.from)]),
      ],
    });
    const view = await render(<RecurringBillsTimelineScreen />, {
      wrapper: wrapper(queryClient),
    });
    expect(await view.findAllByText('Electric')).toHaveLength(2);

    await fireEvent.press(view.getByTestId('mark-today-hidden'));
    await fireEvent.press(await view.findByLabelText('Jump to today'));
    await waitFor(() =>
      expect(mockApi.recurringBillTimeline).toHaveBeenCalledWith(
        currentRange.from,
        currentRange.through,
      ),
    );

    mockApi.recurringBillTimeline.mockClear();
    const refreshControl = latestListProps.refreshControl as ReactElement<{
      onRefresh: () => Promise<unknown>;
    }>;
    await act(async () => {
      await refreshControl.props.onRefresh();
    });
    await waitFor(() => expect(mockApi.recurringBillTimeline).toHaveBeenCalledTimes(3));
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledWith(lowerRange.from, lowerRange.through);
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledWith(
      currentRange.from,
      currentRange.through,
    );
    expect(mockApi.recurringBillTimeline).toHaveBeenCalledWith(upperRange.from, upperRange.through);
  });

  it('preserves cached rows and shows retryable feedback when one refresh range fails', async () => {
    const queryClient = client();
    const today = localToday();
    const currentRange = initialTimelineRange(today);
    const nextRange = nextTimelineRange(currentRange.through, today)!;
    queryClient.setQueryData<TimelineInfiniteData>(['recurring-bills', 'timeline', today], {
      pageParams: [currentRange, nextRange],
      pages: [
        timeline(currentRange.from, currentRange.through, [
          occurrence(currentRange.from, 'Electric'),
        ]),
        timeline(nextRange.from, nextRange.through, [occurrence(nextRange.from, 'Internet')]),
      ],
    });
    mockApi.recurringBillTimeline.mockImplementation(async (from: string, through: string) => {
      if (from === nextRange.from && through === nextRange.through) {
        throw new Error('Refresh failed');
      }
      return timeline(from, through, [occurrence(from, 'Refreshed Electric')]);
    });
    const view = await render(<RecurringBillsTimelineScreen />, {
      wrapper: wrapper(queryClient),
    });
    expect(await view.findByText('Electric')).toBeTruthy();
    expect(view.getByText('Internet')).toBeTruthy();

    const refreshControl = latestListProps.refreshControl as ReactElement<{
      onRefresh: () => Promise<unknown>;
    }>;
    await act(async () => {
      await expect(refreshControl.props.onRefresh()).resolves.toBeUndefined();
    });

    expect(view.getByText('Electric')).toBeTruthy();
    expect(view.getByText('Internet')).toBeTruthy();
    expect(
      await view.findByText('Timeline refresh failed. Existing timeline data is still shown.'),
    ).toBeTruthy();
    const retry = view.getByLabelText('Retry recurring Bill timeline refresh');
    expect(retry).toBeTruthy();

    mockApi.recurringBillTimeline.mockImplementation(async (from: string, through: string) =>
      timeline(from, through, [
        occurrence(from, from === nextRange.from ? 'Internet' : 'Electric'),
      ]),
    );
    await act(async () => {
      await fireEvent.press(retry);
    });

    await waitFor(() =>
      expect(
        view.queryByText('Timeline refresh failed. Existing timeline data is still shown.'),
      ).toBeNull(),
    );
    expect(view.getByText('Electric')).toBeTruthy();
    expect(view.getByText('Internet')).toBeTruthy();
  });

  it('retains a page added while refresh requests are pending', async () => {
    const queryClient = client();
    const today = localToday();
    const currentRange = initialTimelineRange(today);
    const previousRange = previousTimelineRange(currentRange.from, today)!;
    const refreshed = deferred<RecurringBillTimeline>();
    queryClient.setQueryData<TimelineInfiniteData>(['recurring-bills', 'timeline', today], {
      pageParams: [currentRange],
      pages: [
        timeline(currentRange.from, currentRange.through, [
          occurrence(currentRange.from, 'Electric'),
        ]),
      ],
    });
    mockApi.recurringBillTimeline.mockImplementation(async (from: string, through: string) =>
      from === currentRange.from && through === currentRange.through
        ? refreshed.promise
        : timeline(from, through, [occurrence(from)]),
    );
    const view = await render(<RecurringBillsTimelineScreen />, {
      wrapper: wrapper(queryClient),
    });
    expect(await view.findByText('Electric')).toBeTruthy();

    const refreshControl = latestListProps.refreshControl as ReactElement<{
      onRefresh: () => Promise<unknown>;
    }>;
    let refreshPromise: Promise<unknown> = Promise.resolve();
    await act(async () => {
      refreshPromise = refreshControl.props.onRefresh();
      await Promise.resolve();
    });
    await act(async () => {
      queryClient.setQueryData<TimelineInfiniteData>(
        ['recurring-bills', 'timeline', today],
        (data) =>
          appendTimelinePage(data!, previousRange, occurrence(previousRange.from, 'Mortgage')),
      );
      await Promise.resolve();
    });
    expect(await view.findByText('Mortgage')).toBeTruthy();

    await act(async () => {
      refreshed.resolve(
        timeline(currentRange.from, currentRange.through, [
          occurrence(currentRange.from, 'Refreshed Electric'),
        ]),
      );
      await refreshPromise;
    });

    const cached = queryClient.getQueryData<TimelineInfiniteData>([
      'recurring-bills',
      'timeline',
      today,
    ])!;
    expect(cached.pageParams).toEqual([previousRange, currentRange]);
    expect(cached.pages.map((page) => page.from)).toEqual([previousRange.from, currentRange.from]);
    expect(view.getByText('Mortgage')).toBeTruthy();
    expect(await view.findByText('Refreshed Electric')).toBeTruthy();
  });
});

function timeline(
  from: string,
  through: string,
  items: RecurringBillOccurrence[] = [],
): RecurringBillTimeline {
  return { from, items, through };
}

function occurrence(date: string, name = 'Electric'): RecurringBillOccurrence {
  return {
    accountName: null,
    definitionId,
    definitionVersion: 2,
    importCount: 0,
    imports: [],
    name,
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

function endOfMonth(date: string) {
  const [year, month] = date.split('-').map(Number);
  return new Date(Date.UTC(year, month, 0)).toISOString().slice(0, 10);
}

function startOfMonth(date: string) {
  return `${date.slice(0, 8)}01`;
}

function appendTimelinePage(
  data: TimelineInfiniteData,
  range: TimelineRange,
  item: RecurringBillOccurrence,
): TimelineInfiniteData {
  return {
    pageParams: [...data.pageParams, range],
    pages: [...data.pages, timeline(range.from, range.through, [item])],
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}
