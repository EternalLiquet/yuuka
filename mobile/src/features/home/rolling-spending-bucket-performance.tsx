import type { UseQueryResult } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react-native';
import { StyleSheet, View } from 'react-native';

import type { RollingSpendingBucketPerformance } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { SegmentedControl } from '@/components/segmented-control';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export type RollingBucketPeriod = '30' | '90';

const rollingPeriodOptions = [
  { label: '30 days', value: '30' },
  { label: '90 days', value: '90' },
] as const;

export function RollingSpendingBucketPerformanceSection({
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
      style={styles.sectionBody}
    >
      <SegmentedControl
        label="Spending bucket period"
        onChange={onPeriodChange}
        options={rollingPeriodOptions}
        value={period}
      />
      <View style={styles.summaryTextBlock}>
        <AppText variant="label">{title}</AppText>
        {summary && net ? (
          <AppText
            style={[
              styles.netText,
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
        <View style={styles.state}>
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
  metric: { flex: 1, gap: 3, minWidth: 0 },
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  netText: { fontWeight: '700' },
  sectionBody: { gap: 10 },
  state: { alignItems: 'flex-start', gap: 8 },
  summaryTextBlock: { alignItems: 'flex-start', gap: 4 },
});
