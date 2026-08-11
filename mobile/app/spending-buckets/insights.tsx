import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { ArrowLeft, RefreshCw } from 'lucide-react-native';
import { useState } from 'react';
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

const ALL = '__all__';

export default function SpendingInsightsScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [selection, setSelection] = useState(ALL);
  const overall = useQuery({
    queryKey: ['spending-buckets', 'insights', 'all'],
    queryFn: () => api.spendingBucketInsights(),
  });
  const drillDown = useQuery({
    enabled: selection !== ALL,
    queryKey: ['spending-buckets', 'insights', selection],
    queryFn: () => api.spendingBucketInsights(selection),
  });
  const selectedFailed = selection !== ALL && drillDown.isError && !drillDown.data;
  const displayed = selection === ALL ? overall.data : (drillDown.data ?? overall.data);
  const activeQuery = selection === ALL ? overall : drillDown;
  const options = [
    { label: 'All Spending Buckets', value: ALL },
    ...(overall.data?.availableBucketNames ?? []).map((name) => ({ label: name, value: name })),
  ];

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
                value={selection}
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
                  {selection} history could not be loaded. Showing usable overall history.{' '}
                  {displayError(drillDown.error, settings.currencyCode, 'Retry when connected.')}
                </AppText>
                <Button
                  icon={RefreshCw}
                  label="Retry bucket history"
                  onPress={() => void drillDown.refetch()}
                  variant="secondary"
                />
              </View>
            ) : null}
            {activeQuery.isError && activeQuery.data ? <StaleBanner /> : null}
            {selection !== ALL && drillDown.isPending && !drillDown.data ? (
              <YuukaLoadingState message={`Loading ${selection} history...`} minHeight={120} />
            ) : null}
            {displayed ? (
              <InsightsContent
                data={displayed}
                fallbackOverall={selection !== ALL && !drillDown.data}
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
}: {
  data: SpendingBucketInsights;
  fallbackOverall: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const scopeLabel = fallbackOverall
    ? 'All Spending Buckets'
    : data.scope === 'ALL'
      ? 'All Spending Buckets'
      : data.selectedBucketName;
  if (!data.points.length) {
    return (
      <EmptyState
        message={
          data.scope === 'ALL'
            ? 'Add a Spending Bucket to a current or past paycheck to begin a paycheck-based history.'
            : `No recent qualifying paycheck contains ${data.selectedBucketName}. Missing paychecks are not shown as $0.`
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
            accessibilityLabel={`${point.paycheckName}, ${formatDate(point.incomeDate)}. Budgeted ${formatMoney(point.budgetedMinor, settings.currencyCode)}. Spent ${formatMoney(point.spentMinor, settings.currencyCode)}. ${netSentence(point.netMinor, settings.currencyCode)}. ${point.matchingBucketCount} matching buckets.`}
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
            {point.matchingBucketCount > 1 ? (
              <AppText style={{ color: colors.muted }} variant="caption">
                {point.matchingBucketCount} same-name bucket entries combined
              </AppText>
            ) : null}
          </View>
        ))}
      </View>
    </View>
  );
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
});
