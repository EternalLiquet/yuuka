import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import { useFocusEffect, useRouter } from 'expo-router';
import { Plus, RefreshCw, Search } from 'lucide-react-native';
import { useCallback, useEffect, useState } from 'react';
import { AppState, FlatList, RefreshControl, StyleSheet, View } from 'react-native';

import type { RollingSpendingBucketPerformance } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { SegmentedControl } from '@/components/segmented-control';
import {
  EmptyState,
  ErrorState,
  StaleBanner,
  YuukaLoadingState,
  YuukaRefreshIndicator,
} from '@/components/states';
import { formatMoney } from '@/domain/money';
import { PaycheckCard } from '@/features/paychecks/paycheck-card';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

type RollingBucketPeriod = '30' | '90';

const rollingPeriodOptions = [
  { label: '30 days', value: '30' },
  { label: '90 days', value: '90' },
] as const;

export default function ActiveScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [rollingPeriod, setRollingPeriod] = useState<RollingBucketPeriod>('30');
  const query = useQuery({
    queryKey: ['paychecks', 'active'],
    queryFn: api.activePaychecks,
  });
  const bucketPerformanceQuery = useQuery({
    queryKey: ['spending-buckets', 'rolling-days', rollingPeriod, 'current'],
    queryFn: () => api.rollingSpendingBucketPerformance(Number(rollingPeriod) as 30 | 90),
  });
  const { refetch: refetchActivePaychecks } = query;
  const { refetch: refetchBucketPerformance } = bucketPerformanceQuery;
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 1000);

  useFocusEffect(
    useCallback(() => {
      void refetchBucketPerformance();
    }, [refetchBucketPerformance]),
  );

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        void refetchBucketPerformance();
      }
    });
    return () => subscription.remove();
  }, [refetchBucketPerformance]);

  const refresh = useCallback(() => {
    void refetchActivePaychecks();
    void refetchBucketPerformance();
  }, [refetchActivePaychecks, refetchBucketPerformance]);

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading paychecks..." />
      </Screen>
    );
  }

  return (
    <Screen>
      <FlatList
        contentContainerStyle={styles.content}
        data={query.data?.items ?? []}
        keyExtractor={(item) => item.id}
        ListEmptyComponent={
          query.isError && !query.data ? (
            <ErrorState
              message={displayError(
                query.error,
                settings.currencyCode,
                'Check the API connection and try again.',
              )}
              retry={() => query.refetch()}
            />
          ) : (
            <EmptyState
              mascot="wave"
              message="New and reopened paychecks will appear here."
              title="Nothing needs attention"
            />
          )
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Active</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {query.data?.totalItems ?? 0} paycheck{query.data?.totalItems === 1 ? '' : 's'}
                </AppText>
              </View>
              <Button
                icon={Plus}
                label="New paycheck"
                onPress={() => router.push('/paychecks/new')}
              />
            </View>
            <Button
              icon={Search}
              label="Find entry"
              onPress={() => router.push('/search/entries?scope=ACTIVE')}
              variant="secondary"
            />
            <RollingSpendingBucketPerformanceCard
              onPeriodChange={setRollingPeriod}
              period={rollingPeriod}
              query={bucketPerformanceQuery}
            />
            <YuukaRefreshIndicator visible={query.isFetching && Boolean(query.data)} />
            {query.isError && query.data ? <StaleBanner /> : null}
          </View>
        }
        refreshControl={
          <RefreshControl
            colors={['transparent']}
            onRefresh={refresh}
            progressBackgroundColor="transparent"
            refreshing={query.isRefetching}
            tintColor="transparent"
          />
        }
        renderItem={({ item }) => (
          <PaycheckCard onPress={() => router.push(`/paychecks/${item.id}`)} paycheck={item} />
        )}
      />
    </Screen>
  );
}

function RollingSpendingBucketPerformanceCard({
  onPeriodChange,
  period,
  query,
}: {
  onPeriodChange: (period: RollingBucketPeriod) => void;
  period: RollingBucketPeriod;
  query: UseQueryResult<RollingSpendingBucketPerformance, Error>;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const summary = query.data?.summary;
  const title = `Spending Buckets · Last ${period} days`;
  const net = summary ? rollingNetDescription(summary.netMinor, settings.currencyCode) : null;
  return (
    <View
      accessibilityLabel={
        net
          ? `Spending buckets last ${period} days: ${net}`
          : `Spending buckets last ${period} days`
      }
      style={[
        styles.bucketSummary,
        { backgroundColor: colors.surfaceElevated, borderColor: colors.border },
      ]}
    >
      <SegmentedControl
        label="Spending bucket period"
        onChange={onPeriodChange}
        options={rollingPeriodOptions}
        value={period}
      />
      <View style={styles.bucketSummaryTextBlock}>
        <AppText variant="label">{title}</AppText>
        {summary && net ? (
          <AppText
            style={[
              styles.bucketSummaryNetText,
              {
                color:
                  summary.netMinor < 0
                    ? colors.danger
                    : summary.netMinor === 0
                      ? colors.muted
                      : colors.posted,
              },
            ]}
            variant="caption"
          >
            {net}
          </AppText>
        ) : null}
      </View>
      {query.isPending && !query.data ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          Loading bucket summary...
        </AppText>
      ) : null}
      {query.isError && !query.data ? (
        <View style={styles.bucketSummaryState}>
          <AppText style={{ color: colors.muted }} variant="caption">
            {displayError(query.error, settings.currencyCode, 'Bucket data could not be loaded.')}
          </AppText>
          <Button
            icon={RefreshCw}
            label="Retry"
            onPress={() => {
              void query.refetch();
            }}
            variant="secondary"
          />
        </View>
      ) : null}
      {!query.isPending && !query.isError && !summary ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          No Spending Bucket data in the last {period} days.
        </AppText>
      ) : null}
      {summary && net ? (
        <View style={styles.metrics}>
          <Metric
            label="Budgeted"
            value={formatMoney(summary.budgetedMinor, settings.currencyCode)}
          />
          <Metric label="Spent" value={formatMoney(summary.spentMinor, settings.currencyCode)} />
        </View>
      ) : null}
      {query.isError && query.data ? (
        <AppText style={{ color: colors.processing }} variant="caption">
          Summary may be stale
        </AppText>
      ) : null}
    </View>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.metric}>
      <AppText style={{ color: colors.muted }} variant="caption">
        {label}
      </AppText>
      <AppText style={{ color: colors.text, fontWeight: '700' }} variant="caption">
        {value}
      </AppText>
    </View>
  );
}

function rollingNetDescription(netMinor: number, currencyCode: string) {
  if (netMinor > 0) return `Net under by ${formatMoney(netMinor, currencyCode)}`;
  if (netMinor < 0) return `Net over by ${formatMoney(Math.abs(netMinor), currencyCode)}`;
  return 'Net exactly on budget';
}

const styles = StyleSheet.create({
  bucketSummary: { borderRadius: 8, borderWidth: 1, gap: 10, padding: 12 },
  bucketSummaryNetText: { fontWeight: '700' },
  bucketSummaryState: { alignItems: 'flex-start', gap: 8 },
  bucketSummaryTextBlock: { alignItems: 'flex-start', gap: 4 },
  center: { alignItems: 'center', justifyContent: 'center' },
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  header: { gap: 13, marginBottom: 3 },
  metric: { gap: 3 },
  metrics: { gap: 8 },
  titleBlock: { gap: 3 },
  titleRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
});
