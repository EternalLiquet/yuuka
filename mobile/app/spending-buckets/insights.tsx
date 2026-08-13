import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { ArrowLeft, RefreshCw } from 'lucide-react-native';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { SpendingBucketInsights } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { SegmentedControl } from '@/components/segmented-control';
import { EmptyState, ErrorState, StaleBanner, YuukaLoadingState } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { insightNetLabel } from '@/features/spending-insights/chart-math';
import { BudgetSpentChart, NetChart } from '@/features/spending-insights/insight-charts';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const OVERALL_OPTION_ID = 'scope:overall';
const OVERALL_QUERY_KEY = ['spending-buckets', 'insights', 'scope', 'overall'] as const;

function bucketIdentity(bucketName: string) {
  return bucketName.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '').toLowerCase();
}

function bucketQueryKey(bucketName: string | undefined) {
  return [
    'spending-buckets',
    'insights',
    'scope',
    'bucket-name',
    bucketName === undefined ? undefined : bucketIdentity(bucketName),
  ] as const;
}

export default function SpendingInsightsScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const queryClient = useQueryClient();
  const [selection, setSelection] = useState(OVERALL_OPTION_ID);
  const refreshPromise = useRef<Promise<unknown> | null>(null);
  const overall = useQuery({
    queryKey: OVERALL_QUERY_KEY,
    queryFn: () => api.spendingBucketInsights(),
  });
  const bucketOptions = useMemo(
    () =>
      (overall.data?.availableBucketNames ?? []).map((name) => ({
        label: name,
        name,
        value: `bucket:${encodeURIComponent(bucketIdentity(name))}`,
      })),
    [overall.data?.availableBucketNames],
  );
  const selectionAvailable =
    selection === OVERALL_OPTION_ID || bucketOptions.some((option) => option.value === selection);
  const effectiveSelection = selectionAvailable ? selection : OVERALL_OPTION_ID;
  if (!selectionAvailable) setSelection(OVERALL_OPTION_ID);
  const selectedBucketName = bucketOptions.find(
    (option) => option.value === effectiveSelection,
  )?.name;
  const drillDown = useQuery({
    enabled: selectedBucketName !== undefined,
    queryKey: bucketQueryKey(selectedBucketName),
    queryFn: () => api.spendingBucketInsights(selectedBucketName),
  });
  const refetchDrillDown = drillDown.refetch;
  const previousSelectedBucketName = useRef(selectedBucketName);
  useEffect(() => {
    const previousName = previousSelectedBucketName.current;
    previousSelectedBucketName.current = selectedBucketName;
    if (
      previousName !== undefined &&
      selectedBucketName !== undefined &&
      previousName !== selectedBucketName &&
      bucketIdentity(previousName) === bucketIdentity(selectedBucketName)
    ) {
      void refetchDrillDown();
    }
  }, [refetchDrillDown, selectedBucketName]);
  const selectedFailed = selectedBucketName !== undefined && drillDown.isError && !drillDown.data;
  const displayed =
    selectedBucketName === undefined ? overall.data : (drillDown.data ?? overall.data);
  const activeQuery = selectedBucketName === undefined ? overall : drillDown;
  const showStaleRetry =
    !selectedFailed &&
    ((activeQuery.isError && activeQuery.data !== undefined) ||
      (selectedBucketName !== undefined && overall.isError && overall.data !== undefined));
  const refreshing = overall.isFetching || drillDown.isFetching;
  const options = [{ label: 'All Spending Buckets', value: OVERALL_OPTION_ID }, ...bucketOptions];
  const refreshInsights = () => {
    if (refreshPromise.current) return refreshPromise.current;
    const refresh: Promise<unknown> = (
      selectedBucketName === undefined
        ? overall.refetch()
        : (async () => {
            const result = await overall.refetch();
            const refreshedBucketName = result.data?.availableBucketNames.find(
              (name) => `bucket:${encodeURIComponent(bucketIdentity(name))}` === selection,
            );
            if (refreshedBucketName === undefined) return;
            await queryClient.fetchQuery({
              queryFn: () => api.spendingBucketInsights(refreshedBucketName),
              queryKey: bucketQueryKey(refreshedBucketName),
              staleTime: 0,
            });
          })()
    ).catch(() => undefined);
    refreshPromise.current = refresh;
    void refresh.then(() => {
      if (refreshPromise.current === refresh) {
        refreshPromise.current = null;
      }
    });
    return refresh;
  };

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <Pressable
            accessibilityLabel="Back"
            accessibilityRole="button"
            onPress={() => router.back()}
            style={styles.back}
          >
            <ArrowLeft color={colors.text} size={22} />
          </Pressable>
          <View style={styles.headerText}>
            <AppText variant="title">Spending Insights</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              Your 12 most recent paychecks with Spending Buckets
            </AppText>
          </View>
        </View>

        {overall.isPending && !overall.data ? (
          <YuukaLoadingState message="Loading Spending Insights..." />
        ) : null}
        {overall.isError && !overall.data ? (
          <ErrorState
            message={displayError(
              overall.error,
              settings.currencyCode,
              'Spending Insights could not be loaded.',
            )}
            retry={() => void overall.refetch()}
          />
        ) : null}
        {overall.data ? (
          <>
            <View style={styles.selectorBlock}>
              <AppText variant="label">History scope</AppText>
              <SegmentedControl
                label="Spending Insights bucket"
                onChange={setSelection}
                options={options}
                value={effectiveSelection}
              />
              <AppText style={{ color: colors.muted }} variant="caption">
                Bucket names are grouped only when their trimmed names match exactly, ignoring
                capitalization. Renamed or similarly named buckets have separate histories.
              </AppText>
            </View>

            {selectedFailed ? (
              <View
                accessibilityLiveRegion="polite"
                style={[styles.notice, { backgroundColor: colors.processingSoft }]}
              >
                <AppText style={{ color: colors.processing }} variant="caption">
                  {selectedBucketName} history could not be loaded. Showing usable overall history.{' '}
                  {displayError(drillDown.error, settings.currencyCode, 'Retry when connected.')}
                </AppText>
                <Button
                  accessibilityLabel="Retry Spending Insights refresh"
                  icon={RefreshCw}
                  label="Retry bucket history"
                  loading={refreshing}
                  onPress={() => void refreshInsights()}
                  variant="secondary"
                />
              </View>
            ) : null}
            {showStaleRetry ? (
              <View style={styles.staleActions}>
                <StaleBanner />
                <Button
                  accessibilityLabel="Retry Spending Insights refresh"
                  icon={RefreshCw}
                  label="Retry"
                  loading={refreshing}
                  onPress={() => void refreshInsights()}
                  variant="secondary"
                />
              </View>
            ) : null}
            {selectedBucketName !== undefined && drillDown.isPending && !drillDown.data ? (
              <YuukaLoadingState
                message={`Loading ${selectedBucketName} history...`}
                minHeight={120}
              />
            ) : null}
            {displayed ? (
              <InsightsContent
                data={displayed}
                fallbackOverall={selectedBucketName !== undefined && !drillDown.data}
                selectedBucketName={selectedBucketName}
              />
            ) : null}
          </>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

function InsightsContent({
  data,
  fallbackOverall,
  selectedBucketName,
}: {
  data: SpendingBucketInsights;
  fallbackOverall: boolean;
  selectedBucketName: string | undefined;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const overallScope = fallbackOverall || data.scope === 'ALL';
  const scopeLabel = fallbackOverall
    ? 'All Spending Buckets'
    : data.scope === 'ALL'
      ? 'All Spending Buckets'
      : (selectedBucketName ?? data.selectedBucketName);
  if (!data.points.length) {
    return (
      <EmptyState
        message={
          data.scope === 'ALL'
            ? 'Add a Spending Bucket to a current or past paycheck to begin a paycheck-based history.'
            : `No recent qualifying paycheck contains ${selectedBucketName ?? data.selectedBucketName}. Missing paychecks are not shown as $0.`
        }
        title="No Spending Bucket history"
      />
    );
  }
  return (
    <View accessibilityLabel={`${scopeLabel} insight history`} style={styles.results}>
      <View style={styles.scopeTitle}>
        <AppText variant="title">{scopeLabel}</AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          {data.points.length} paycheck{data.points.length === 1 ? '' : 's'} through {data.asOfDate}
        </AppText>
      </View>
      <View style={styles.card}>
        <AppText variant="label">Budgeted vs Spent</AppText>
        <BudgetSpentChart points={data.points} />
      </View>
      <View style={styles.card}>
        <AppText variant="label">Net Under / Over</AppText>
        <NetChart points={data.points} />
      </View>
      <View style={styles.history}>
        <AppText variant="title">Paycheck details</AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          These exact values are the accessible record for both graphs.
        </AppText>
        {data.points.map((point) => (
          <View
            accessibilityLabel={`${point.paycheckName}, ${formatDate(point.incomeDate)}. Budgeted ${formatMoney(point.budgetedMinor, settings.currencyCode)}. Spent ${formatMoney(point.spentMinor, settings.currencyCode)}. ${netSentence(point.netMinor, settings.currencyCode)}. ${bucketCountDescription(point.matchingBucketCount, overallScope)}.`}
            key={point.paycheckId}
            style={[styles.point, { borderColor: colors.border }]}
          >
            <View style={styles.pointHeader}>
              <AppText style={styles.flexText} variant="label">
                {point.paycheckName}
              </AppText>
              <AppText style={{ color: colors.muted }} variant="caption">
                {formatDate(point.incomeDate)}
              </AppText>
            </View>
            <AppText variant="caption">
              Budgeted {formatMoney(point.budgetedMinor, settings.currencyCode)} · Spent{' '}
              {formatMoney(point.spentMinor, settings.currencyCode)}
            </AppText>
            <AppText
              style={{
                color:
                  point.netMinor < 0
                    ? colors.danger
                    : point.netMinor > 0
                      ? colors.posted
                      : colors.muted,
                fontWeight: '700',
              }}
              variant="caption"
            >
              {insightNetLabel(point)} · {netSentence(point.netMinor, settings.currencyCode)}
            </AppText>
            {overallScope || point.matchingBucketCount > 1 ? (
              <AppText style={{ color: colors.muted }} variant="caption">
                {bucketCountDescription(point.matchingBucketCount, overallScope)}
              </AppText>
            ) : null}
          </View>
        ))}
      </View>
    </View>
  );
}

function bucketCountDescription(count: number, overallScope: boolean) {
  if (overallScope) {
    return `${count} Spending Bucket ${count === 1 ? 'entry' : 'entries'} included`;
  }
  if (count > 1) return `${count} same-name bucket entries combined`;
  return '1 selected bucket entry included';
}

function netSentence(netMinor: number, currencyCode: string) {
  if (netMinor > 0) return `Under by ${formatMoney(netMinor, currencyCode)}`;
  if (netMinor < 0) return `Over by ${formatMoney(Math.abs(netMinor), currencyCode)}`;
  return 'Exactly on budget';
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).format(new Date(`${value}T00:00:00`));
}

const styles = StyleSheet.create({
  back: { alignItems: 'center', justifyContent: 'center', minHeight: 48, minWidth: 48 },
  card: { gap: 12, minWidth: 0 },
  content: { gap: 20, padding: 16, paddingBottom: 40 },
  flexText: { flex: 1, minWidth: 0 },
  header: { alignItems: 'flex-start', flexDirection: 'row', gap: 4 },
  headerText: { flex: 1, gap: 4, minWidth: 0, paddingTop: 5 },
  history: { gap: 10 },
  notice: { alignItems: 'flex-start', borderRadius: 8, gap: 10, padding: 12 },
  point: { borderTopWidth: 1, gap: 5, paddingVertical: 12 },
  pointHeader: { alignItems: 'flex-start', flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  results: { gap: 22 },
  scopeTitle: { gap: 3 },
  selectorBlock: { gap: 10 },
  staleActions: { alignItems: 'flex-start', gap: 10 },
});
