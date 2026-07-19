import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { Plus, Settings2 } from 'lucide-react-native';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  View,
} from 'react-native';
import type { ViewToken } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import type { RecurringBillOccurrence } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppMenuButton } from '@/components/app-menu';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { formatMoney } from '@/domain/money';
import {
  groupTimeline,
  initialTimelineRange,
  insertTimelinePage,
  nextTimelineRange,
  previousTimelineRange,
  timelineContainsDate,
} from '@/features/recurring-bills/timeline';
import type {
  TimelineDay,
  TimelineInfiniteData,
  TimelineRange,
} from '@/features/recurring-bills/timeline';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function RecurringBillsTimelineScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const insets = useSafeAreaInsets();
  const [today] = useState(() => todayDate());
  const initialRange = useMemo(() => initialTimelineRange(today), [today]);
  const queryKey = useMemo(() => ['recurring-bills', 'timeline', today] as const, [today]);
  const listRef = useRef<FlatList<TimelineDay>>(null);
  const didInitialPosition = useRef(false);
  const edgeLoadingEnabled = useRef(false);
  const userScrolled = useRef(false);
  const previousRequestPending = useRef(false);
  const nextRequestPending = useRef(false);
  const [todayVisible, setTodayVisible] = useState(true);
  const [jumpPending, setJumpPending] = useState(false);
  const [jumpError, setJumpError] = useState(false);
  const onViewableItemsChanged = useCallback(
    ({ viewableItems }: { viewableItems: ViewToken<TimelineDay>[] }) => {
      setTodayVisible(viewableItems.some((item) => item.item.date === today));
    },
    [today],
  );
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }: { pageParam: TimelineRange }) =>
      api.recurringBillTimeline(pageParam.from, pageParam.through),
    initialPageParam: initialRange,
    getPreviousPageParam: (firstPage) => previousTimelineRange(firstPage.from),
    getNextPageParam: (lastPage) => nextTimelineRange(lastPage.through),
  });
  const occurrences = useMemo(
    () => query.data?.pages.flatMap((page) => page.items) ?? [],
    [query.data?.pages],
  );
  const days = useMemo(() => groupTimeline(occurrences, today), [occurrences, today]);
  const todayIndex = days.findIndex((day) => day.date === today);
  const currentRangeLoaded = timelineContainsDate(query.data?.pages ?? [], today);

  const scrollToToday = useCallback(
    (animated: boolean) => {
      if (todayIndex < 0) return;
      listRef.current?.scrollToIndex({ animated, index: todayIndex, viewPosition: 0.4 });
    },
    [todayIndex],
  );

  useEffect(() => {
    if (didInitialPosition.current || todayIndex < 0 || !currentRangeLoaded) return;
    didInitialPosition.current = true;
    const timer = setTimeout(() => {
      scrollToToday(false);
      edgeLoadingEnabled.current = true;
    }, 0);
    return () => clearTimeout(timer);
  }, [currentRangeLoaded, scrollToToday, todayIndex]);

  const loadPrevious = useCallback(
    async (retry = false) => {
      if (
        !edgeLoadingEnabled.current ||
        !userScrolled.current ||
        previousRequestPending.current ||
        query.isFetchingPreviousPage ||
        (!retry && query.isFetchPreviousPageError)
      ) {
        return;
      }
      previousRequestPending.current = true;
      try {
        await query.fetchPreviousPage();
      } finally {
        previousRequestPending.current = false;
      }
    },
    [query],
  );

  const loadNext = useCallback(
    async (retry = false) => {
      if (
        !edgeLoadingEnabled.current ||
        !userScrolled.current ||
        nextRequestPending.current ||
        query.isFetchingNextPage ||
        (!retry && query.isFetchNextPageError)
      ) {
        return;
      }
      nextRequestPending.current = true;
      try {
        await query.fetchNextPage();
      } finally {
        nextRequestPending.current = false;
      }
    },
    [query],
  );

  const jumpToToday = useCallback(async () => {
    setJumpError(false);
    if (currentRangeLoaded) {
      scrollToToday(true);
      return;
    }

    setJumpPending(true);
    didInitialPosition.current = true;
    try {
      const page = await api.recurringBillTimeline(initialRange.from, initialRange.through);
      queryClient.setQueryData<TimelineInfiniteData>(queryKey, (data) =>
        data ? insertTimelinePage(data, page) : { pageParams: [initialRange], pages: [page] },
      );
      const restoredIndex = groupTimeline([...occurrences, ...page.items], today).findIndex(
        (day) => day.date === today,
      );
      await new Promise<void>((resolve) => {
        setTimeout(() => {
          if (restoredIndex >= 0) {
            listRef.current?.scrollToIndex({
              animated: true,
              index: restoredIndex,
              viewPosition: 0.4,
            });
          }
          edgeLoadingEnabled.current = true;
          resolve();
        }, 0);
      });
    } catch {
      setJumpError(true);
    } finally {
      setJumpPending(false);
    }
  }, [
    api,
    currentRangeLoaded,
    initialRange,
    occurrences,
    queryClient,
    queryKey,
    scrollToToday,
    today,
  ]);

  if (query.isPending && !query.data) {
    return <YuukaLoadingState message="Loading recurring Bill timeline..." />;
  }
  if (query.isError && !query.data) {
    return (
      <ErrorState
        message={displayError(query.error, settings.currencyCode, 'The timeline could not load.')}
        retry={() => query.refetch()}
      />
    );
  }

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: 'Recurring Bills',
          headerLeft: () => <AppMenuButton />,
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <Screen>
        <FlatList
          contentContainerStyle={[styles.list, { paddingBottom: 160 + insets.bottom }]}
          data={days}
          keyExtractor={(day) => day.date}
          ListHeaderComponent={
            <View style={styles.header}>
              <EdgeFeedback
                direction="earlier"
                error={query.isFetchPreviousPageError}
                loading={query.isFetchingPreviousPage}
                onRetry={() => void loadPrevious(true)}
              />
              <View style={styles.heading}>
                <AppText variant="title">Recurring Bills</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  Monthly Bills around today. Past dates are informational, not overdue labels.
                </AppText>
              </View>
              <Button
                icon={Settings2}
                label="Manage recurring Bills"
                onPress={() => router.push('/recurring-bills/manage')}
                variant="secondary"
              />
            </View>
          }
          ListFooterComponent={
            <EdgeFeedback
              direction="later"
              error={query.isFetchNextPageError}
              loading={query.isFetchingNextPage}
              onRetry={() => void loadNext(true)}
            />
          }
          maintainVisibleContentPosition={{ minIndexForVisible: 0 }}
          onEndReached={() => loadNext()}
          onEndReachedThreshold={0.35}
          onScrollBeginDrag={() => {
            userScrolled.current = true;
          }}
          onScrollToIndexFailed={(info) => {
            listRef.current?.scrollToOffset({
              animated: false,
              offset: info.averageItemLength * info.index,
            });
            setTimeout(
              () =>
                listRef.current?.scrollToIndex({
                  animated: true,
                  index: info.index,
                  viewPosition: 0.4,
                }),
              60,
            );
          }}
          onStartReached={() => loadPrevious()}
          onStartReachedThreshold={0.35}
          onViewableItemsChanged={onViewableItemsChanged}
          ref={listRef}
          refreshControl={
            <RefreshControl
              accessibilityLabel="Refresh recurring Bill timeline"
              onRefresh={() => query.refetch()}
              refreshing={
                query.isRefetching && !query.isFetchingPreviousPage && !query.isFetchingNextPage
              }
              tintColor={colors.accent}
            />
          }
          renderItem={({ item }) => (
            <TimelineDayRow
              day={item}
              onEdit={(definitionId) => router.push(`/recurring-bills/${definitionId}/edit`)}
              today={today}
            />
          )}
          viewabilityConfig={{ itemVisiblePercentThreshold: 30 }}
        />
        {!todayVisible || !currentRangeLoaded ? (
          <View style={[styles.jumpAction, { bottom: 88 + insets.bottom }]}>
            <Button
              label={jumpError ? 'Retry jump to today' : 'Jump to today'}
              loading={jumpPending}
              onPress={() => jumpToToday()}
              variant="secondary"
            />
          </View>
        ) : null}
        <Pressable
          accessibilityLabel="New recurring Bill"
          accessibilityRole="button"
          onPress={() => router.push('/recurring-bills/new')}
          style={({ pressed }) => [
            styles.floatingAction,
            {
              backgroundColor: colors.accent,
              bottom: 18 + insets.bottom,
              shadowColor: colors.text,
            },
            pressed && styles.pressedAction,
          ]}
          testID="recurring-bills-floating-create"
        >
          <Plus color={colors.accentText} size={28} strokeWidth={2.5} />
        </Pressable>
      </Screen>
    </>
  );
}

function EdgeFeedback({
  direction,
  error,
  loading,
  onRetry,
}: {
  direction: 'earlier' | 'later';
  error: boolean;
  loading: boolean;
  onRetry: () => void;
}) {
  const { colors } = useAppTheme();
  if (!error && !loading) return null;
  if (loading) {
    return (
      <View accessibilityLiveRegion="polite" style={styles.edgeFeedback}>
        <ActivityIndicator color={colors.accent} size="small" />
        <AppText style={{ color: colors.muted }} variant="caption">
          Loading {direction} timeline data…
        </AppText>
      </View>
    );
  }
  return (
    <View accessibilityLiveRegion="polite" style={styles.edgeError}>
      <AppText style={{ color: colors.danger }} variant="caption">
        {direction === 'earlier'
          ? 'Earlier timeline data could not load.'
          : 'Later timeline data could not load.'}
      </AppText>
      <Button
        accessibilityLabel={`Retry ${direction} timeline data`}
        label="Retry"
        onPress={onRetry}
        variant="ghost"
      />
    </View>
  );
}

function TimelineDayRow({
  day,
  onEdit,
  today,
}: {
  day: TimelineDay;
  onEdit: (definitionId: string) => void;
  today: string;
}) {
  const { colors } = useAppTheme();
  const isToday = day.date === today;
  return (
    <View style={styles.day}>
      {isToday ? (
        <View accessibilityLabel={`Today, ${formatLongDate(day.date)}`} style={styles.todayDivider}>
          <View style={[styles.line, { backgroundColor: colors.accent }]} />
          <AppText style={{ color: colors.accent }} variant="label">
            TODAY · {ordinal(new Date(`${day.date}T00:00:00`).getDate())}
          </AppText>
          <View style={[styles.line, { backgroundColor: colors.accent }]} />
        </View>
      ) : (
        <AppText variant="label">{formatLongDate(day.date)}</AppText>
      )}
      {!day.items.length ? (
        <AppText style={{ color: colors.muted }}>No recurring Bills today</AppText>
      ) : (
        day.items.map((item) => (
          <OccurrenceCard
            item={item}
            key={item.definitionId}
            onEdit={() => onEdit(item.definitionId)}
          />
        ))
      )}
    </View>
  );
}

function OccurrenceCard({ item, onEdit }: { item: RecurringBillOccurrence; onEdit: () => void }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const posted =
    item.imports.length > 0 && item.imports.every((entry) => entry.status === 'POSTED');
  return (
    <Pressable
      accessibilityActions={[{ label: 'Edit recurring Bill', name: 'activate' }]}
      accessibilityHint="Long press to edit the recurring Bill definition"
      accessibilityLabel={`${item.name}, ${formatMoney(item.typicalAmountMinor, settings.currencyCode)}, ${item.paymentMethod === 'MANUAL' ? 'Manual' : 'Autopay'}`}
      accessibilityRole="button"
      delayLongPress={500}
      onAccessibilityAction={({ nativeEvent }) => {
        if (nativeEvent.actionName === 'activate') onEdit();
      }}
      onLongPress={onEdit}
      style={({ pressed }) => [
        styles.card,
        { backgroundColor: colors.surface, borderColor: colors.border },
        posted && styles.posted,
        pressed && styles.pressedCard,
      ]}
    >
      <View style={styles.cardHeading}>
        <AppText variant="label">{item.name}</AppText>
        <AppText variant="money">
          {formatMoney(item.typicalAmountMinor, settings.currencyCode)}
        </AppText>
      </View>
      <AppText style={{ color: colors.muted }} variant="caption">
        {item.paymentMethod === 'MANUAL' ? 'Manual' : 'Autopay'}
        {item.importCount
          ? ` · Imported ${item.importCount} time${item.importCount === 1 ? '' : 's'}`
          : ''}
      </AppText>
      {item.imports.map((entry) => (
        <AppText key={entry.entryId} style={{ color: colors.muted }} variant="caption">
          Added to {entry.paycheckName} · {statusLabel(entry.status)}
        </AppText>
      ))}
    </Pressable>
  );
}

function todayDate() {
  const value = new Date();
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
}

function formatLongDate(date: string) {
  return new Intl.DateTimeFormat(undefined, { month: 'long', day: 'numeric' }).format(
    new Date(`${date}T00:00:00`),
  );
}

function ordinal(day: number) {
  const mod100 = day % 100;
  if (mod100 >= 11 && mod100 <= 13) return `${day}th`;
  if (day % 10 === 1) return `${day}st`;
  if (day % 10 === 2) return `${day}nd`;
  if (day % 10 === 3) return `${day}rd`;
  return `${day}th`;
}

function statusLabel(status: 'NOT_PAID' | 'PROCESSING' | 'POSTED') {
  if (status === 'NOT_PAID') return 'Not Paid';
  if (status === 'PROCESSING') return 'Processing';
  return 'Posted';
}

const styles = StyleSheet.create({
  card: { borderRadius: 8, borderWidth: 1, gap: 5, padding: 13 },
  cardHeading: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
  day: { gap: 10, paddingVertical: 11 },
  edgeError: { alignItems: 'center', gap: 4, paddingVertical: 8 },
  edgeFeedback: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'center',
    paddingVertical: 10,
  },
  floatingAction: {
    alignItems: 'center',
    borderRadius: 29,
    elevation: 7,
    height: 58,
    justifyContent: 'center',
    position: 'absolute',
    right: 18,
    shadowOffset: { height: 3, width: 0 },
    shadowOpacity: 0.24,
    shadowRadius: 5,
    width: 58,
  },
  header: { gap: 10, paddingBottom: 12 },
  heading: { gap: 5, paddingBottom: 4 },
  jumpAction: { position: 'absolute', right: 18 },
  line: { flex: 1, height: 1 },
  list: { padding: 18 },
  posted: { opacity: 0.68 },
  pressedAction: { opacity: 0.72, transform: [{ scale: 0.96 }] },
  pressedCard: { opacity: 0.74 },
  todayDivider: { alignItems: 'center', flexDirection: 'row', gap: 10 },
});
