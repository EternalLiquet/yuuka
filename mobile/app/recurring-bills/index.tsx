import { useQuery } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { ListPlus, Settings2 } from 'lucide-react-native';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';
import type { ViewToken } from 'react-native';

import type { RecurringBillOccurrence } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppMenuButton } from '@/components/app-menu';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';
import { groupTimeline, TimelineDay } from '@/features/recurring-bills/timeline';

export default function RecurringBillsTimelineScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [today] = useState(() => todayDate());
  const from = useMemo(() => addDays(today, -45), [today]);
  const through = useMemo(() => addDays(today, 90), [today]);
  const listRef = useRef<FlatList<TimelineDay>>(null);
  const [todayVisible, setTodayVisible] = useState(true);
  const onViewableItemsChanged = useCallback(
    ({ viewableItems }: { viewableItems: ViewToken<TimelineDay>[] }) => {
      setTodayVisible(viewableItems.some((item) => item.item.date === today));
    },
    [today],
  );
  const query = useQuery({
    queryKey: ['recurring-bills', 'timeline', from, through],
    queryFn: () => api.recurringBillTimeline(from, through),
  });
  const days = useMemo(
    () => groupTimeline(query.data?.items ?? [], today),
    [query.data?.items, today],
  );
  const todayIndex = days.findIndex((day) => day.date === today);

  useEffect(() => {
    if (todayIndex < 0) return;
    const timer = setTimeout(() => {
      listRef.current?.scrollToIndex({ animated: false, index: todayIndex, viewPosition: 0.4 });
    }, 0);
    return () => clearTimeout(timer);
  }, [todayIndex]);

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
          contentContainerStyle={styles.list}
          data={days}
          keyExtractor={(day) => day.date}
          ListHeaderComponent={
            <View style={styles.header}>
              <View style={styles.heading}>
                <AppText variant="title">Recurring Bills</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  Monthly Bills around today. Past dates are informational, not overdue labels.
                </AppText>
              </View>
              <Button
                icon={ListPlus}
                label="New recurring Bill"
                onPress={() => router.push('/recurring-bills/new')}
              />
              <Button
                icon={Settings2}
                label="Manage recurring Bills"
                onPress={() => router.push('/recurring-bills/manage')}
                variant="secondary"
              />
              {!todayVisible ? (
                <Button
                  label="Jump to today"
                  onPress={() =>
                    listRef.current?.scrollToIndex({
                      animated: true,
                      index: todayIndex,
                      viewPosition: 0.4,
                    })
                  }
                  variant="ghost"
                />
              ) : null}
            </View>
          }
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
          onViewableItemsChanged={onViewableItemsChanged}
          ref={listRef}
          refreshControl={
            <RefreshControl
              onRefresh={() => void query.refetch()}
              refreshing={query.isRefetching}
              tintColor={colors.accent}
            />
          }
          renderItem={({ item }) => <TimelineDayRow day={item} today={today} />}
          viewabilityConfig={{ itemVisiblePercentThreshold: 30 }}
        />
      </Screen>
    </>
  );
}

function TimelineDayRow({ day, today }: { day: TimelineDay; today: string }) {
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
        day.items.map((item) => <OccurrenceCard item={item} key={item.definitionId} />)
      )}
    </View>
  );
}

function OccurrenceCard({ item }: { item: RecurringBillOccurrence }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const posted =
    item.imports.length > 0 && item.imports.every((entry) => entry.status === 'POSTED');
  return (
    <View
      accessibilityLabel={`${item.name}, ${formatMoney(item.typicalAmountMinor, settings.currencyCode)}, ${item.paymentMethod === 'MANUAL' ? 'Manual' : 'Autopay'}`}
      style={[
        styles.card,
        { backgroundColor: colors.surface, borderColor: colors.border },
        posted && styles.posted,
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
    </View>
  );
}

function addDays(date: string, days: number) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
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
  header: { gap: 10, paddingBottom: 12 },
  heading: { gap: 5, paddingBottom: 4 },
  line: { flex: 1, height: 1 },
  list: { padding: 18, paddingBottom: 48 },
  posted: { opacity: 0.68 },
  todayDivider: { alignItems: 'center', flexDirection: 'row', gap: 10 },
});
