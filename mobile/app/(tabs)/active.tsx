import { useQuery } from '@tanstack/react-query';
import type { UseQueryResult } from '@tanstack/react-query';
import { useFocusEffect, useRouter } from 'expo-router';
import { Plus, Search } from 'lucide-react-native';
import { useCallback, useEffect } from 'react';
import { AppState, FlatList, RefreshControl, StyleSheet, View } from 'react-native';

import type { RollingSpendingBucketPerformance } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
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

export default function ActiveScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const query = useQuery({
    queryKey: ['paychecks', 'active'],
    queryFn: api.activePaychecks,
  });
  const bucketPerformanceQuery = useQuery({
    queryKey: ['spending-buckets', 'rolling-90-days', 'current'],
    queryFn: () => api.rollingSpendingBucketPerformance(),
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
            <RollingSpendingBucketPerformanceCard query={bucketPerformanceQuery} />
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
  query,
}: {
  query: UseQueryResult<RollingSpendingBucketPerformance, Error>;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const summary = query.data?.summary;

  if (query.isPending && !query.data) {
    return (
      <View
        style={[
          styles.bucketSummary,
          { backgroundColor: colors.surfaceElevated, borderColor: colors.border },
        ]}
      >
        <AppText variant="label">Spending Buckets · Last 90 days</AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          Loading bucket summary...
        </AppText>
      </View>
    );
  }

  if (!summary) {
    return null;
  }

  const net = rollingNetDescription(summary.netMinor, settings.currencyCode);
  return (
    <View
      accessibilityLabel={`Spending buckets last 90 days: ${net}`}
      style={[
        styles.bucketSummary,
        { backgroundColor: colors.surfaceElevated, borderColor: colors.border },
      ]}
    >
      <View style={styles.bucketSummaryHeader}>
        <AppText variant="label">Spending Buckets · Last 90 days</AppText>
        <AppText
          style={{
            color:
              summary.netMinor < 0
                ? colors.danger
                : summary.netMinor === 0
                  ? colors.muted
                  : colors.posted,
            fontWeight: '700',
          }}
          variant="caption"
        >
          {net}
        </AppText>
      </View>
      <View style={styles.metrics}>
        <Metric
          label="Budgeted"
          value={formatMoney(summary.budgetedMinor, settings.currencyCode)}
        />
        <Metric label="Spent" value={formatMoney(summary.spentMinor, settings.currencyCode)} />
      </View>
      {query.isError ? (
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
  bucketSummaryHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'space-between',
  },
  center: { alignItems: 'center', justifyContent: 'center' },
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  header: { gap: 13, marginBottom: 3 },
  metric: { flex: 1, gap: 3 },
  metrics: { flexDirection: 'row', gap: 8 },
  titleBlock: { gap: 3 },
  titleRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
});
