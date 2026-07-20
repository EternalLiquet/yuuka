import { useQuery } from '@tanstack/react-query';
import { useFocusEffect, useRouter } from 'expo-router';
import { ChevronRight, Plus, RefreshCw } from 'lucide-react-native';
import { useCallback, useEffect, useState } from 'react';
import { AppState, Pressable, RefreshControl, ScrollView, StyleSheet, View } from 'react-native';

import type {
  DashboardAttentionItem,
  DashboardPaycheckPreview,
  DashboardSummary,
  RecurringBillOccurrence,
} from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { StaleBanner } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { addCalendarDays, dateInTimeZone } from '@/features/home/dashboard-dates';
import {
  RollingBucketPeriod,
  RollingSpendingBucketPerformanceSection,
} from '@/features/home/rolling-spending-bucket-performance';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

type UpcomingRecurringBills = {
  from: string;
  through: string;
  items: RecurringBillOccurrence[];
};

export default function HomeScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const [rollingPeriod, setRollingPeriod] = useState<RollingBucketPeriod>('30');
  const [refreshing, setRefreshing] = useState(false);
  const summaryQuery = useQuery({
    queryKey: ['dashboard', 'summary'],
    queryFn: api.dashboardSummary,
  });
  const bucketQuery = useQuery({
    queryKey: ['spending-buckets', 'rolling-days', rollingPeriod, 'current'],
    queryFn: () => api.rollingSpendingBucketPerformance(Number(rollingPeriod) as 30 | 90),
  });
  const recurringQuery = useQuery({
    queryKey: ['dashboard', 'upcoming-recurring-bills'],
    queryFn: async (): Promise<UpcomingRecurringBills> => {
      const owner = await api.me();
      const from = dateInTimeZone(new Date(), owner.timezone);
      const through = addCalendarDays(from, owner.recurringBillSuggestionDays);
      const timeline = await api.recurringBillTimeline(from, through);
      return {
        from,
        through,
        items: timeline.items
          .slice()
          .sort(
            (left, right) =>
              left.occurrenceDate.localeCompare(right.occurrenceDate) ||
              left.name.localeCompare(right.name, undefined, { sensitivity: 'base' }) ||
              left.definitionId.localeCompare(right.definitionId),
          )
          .slice(0, 3),
      };
    },
  });

  const { refetch: refetchBucket } = bucketQuery;
  useFocusEffect(
    useCallback(() => {
      void refetchBucket();
    }, [refetchBucket]),
  );
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') void refetchBucket();
    });
    return () => subscription.remove();
  }, [refetchBucket]);

  const refreshAll = useCallback(async () => {
    setRefreshing(true);
    try {
      await Promise.allSettled([
        summaryQuery.refetch(),
        bucketQuery.refetch(),
        recurringQuery.refetch(),
      ]);
    } finally {
      setRefreshing(false);
    }
  }, [bucketQuery, recurringQuery, summaryQuery]);

  return (
    <Screen>
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            accessibilityLabel="Refresh Home dashboard"
            colors={['transparent']}
            onRefresh={() => void refreshAll()}
            progressBackgroundColor="transparent"
            refreshing={refreshing}
            tintColor="transparent"
          />
        }
      >
        <View style={styles.header} testID="home-header">
          <View style={styles.titleBlock}>
            <AppText variant="title">Home</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              What this paycheck still needs to accomplish
            </AppText>
          </View>
          <Button icon={Plus} label="New paycheck" onPress={() => router.push('/paychecks/new')} />
        </View>

        <Section title="Needs Attention">
          <SummaryQueryState query={summaryQuery} retryLabel="Retry dashboard summary" />
          {summaryQuery.data ? (
            summaryQuery.data.needsAttention.length ? (
              <View style={styles.rows}>
                {summaryQuery.data.needsAttention.map((item) => (
                  <AttentionRow
                    item={item}
                    key={`${item.kind}-${item.entryId ?? item.paycheckId ?? item.expenseLedgerId}`}
                    onPress={() => openAttentionItem(router, item)}
                  />
                ))}
              </View>
            ) : (
              <AppText style={{ color: colors.muted }} variant="caption">
                Nothing needs attention right now.
              </AppText>
            )
          ) : null}
        </Section>

        <Section
          actionLabel="View Active"
          onAction={() => router.push('/(tabs)/active')}
          title="Active Paychecks"
        >
          {summaryQuery.data ? (
            <ActiveSummary
              data={summaryQuery.data}
              onOpen={(paycheckId) => router.push(`/paychecks/${paycheckId}`)}
            />
          ) : null}
        </Section>

        <Section title="Spending Buckets">
          <RollingSpendingBucketPerformanceSection
            onPeriodChange={setRollingPeriod}
            period={rollingPeriod}
            query={bucketQuery}
          />
        </Section>

        <Section
          actionLabel="View Timeline"
          onAction={() => router.push('/recurring-bills')}
          title="Upcoming Recurring Bills"
        >
          <RecurringQueryState query={recurringQuery} />
          {recurringQuery.data ? (
            recurringQuery.data.items.length ? (
              <View style={styles.rows}>
                {recurringQuery.data.items.map((item) => (
                  <RecurringBillRow
                    item={item}
                    key={`${item.definitionId}-${item.occurrenceDate}`}
                  />
                ))}
              </View>
            ) : (
              <AppText style={{ color: colors.muted }} variant="caption">
                No recurring Bills are due in the current suggestion window.
              </AppText>
            )
          ) : null}
        </Section>

        <Section title="Financial Positions">
          {summaryQuery.data ? (
            <FinancialPositions
              data={summaryQuery.data}
              onExpenseLists={() => router.push('/(tabs)/expense-ledgers')}
              onPaybacks={() => router.push('/(tabs)/paybacks')}
              onPlannedSavings={() => router.push('/(tabs)/planned-savings')}
            />
          ) : null}
        </Section>
      </ScrollView>
    </Screen>
  );
}

function Section({
  actionLabel,
  children,
  onAction,
  title,
}: {
  actionLabel?: string;
  children: React.ReactNode;
  onAction?: () => void;
  title: string;
}) {
  const { colors } = useAppTheme();
  return (
    <View
      accessibilityLabel={`${title} section`}
      style={[styles.section, { borderTopColor: colors.border }]}
    >
      <View style={styles.sectionHeader}>
        <AppText style={styles.sectionTitle} variant="label">
          {title}
        </AppText>
        {actionLabel && onAction ? (
          <Pressable
            accessibilityLabel={actionLabel}
            accessibilityRole="button"
            hitSlop={8}
            onPress={onAction}
            style={styles.sectionAction}
          >
            <AppText style={{ color: colors.accent }} variant="caption">
              {actionLabel}
            </AppText>
            <ChevronRight color={colors.accent} size={16} />
          </Pressable>
        ) : null}
      </View>
      {children}
    </View>
  );
}

function SummaryQueryState({
  query,
  retryLabel,
}: {
  query: ReturnType<typeof useQuery<DashboardSummary, Error>>;
  retryLabel: string;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  if (query.isPending && !query.data) {
    return (
      <AppText style={{ color: colors.muted }} variant="caption">
        Loading summary...
      </AppText>
    );
  }
  if (query.isError && !query.data) {
    return (
      <View style={styles.inlineState}>
        <AppText style={{ color: colors.danger }} variant="caption">
          {displayError(
            query.error,
            settings.currencyCode,
            'Dashboard summary could not be loaded.',
          )}
        </AppText>
        <Button
          accessibilityLabel={retryLabel}
          icon={RefreshCw}
          label="Retry"
          onPress={() => void query.refetch()}
          variant="secondary"
        />
      </View>
    );
  }
  return query.isError && query.data ? <StaleBanner /> : null;
}

function RecurringQueryState({
  query,
}: {
  query: ReturnType<typeof useQuery<UpcomingRecurringBills, Error>>;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  if (query.isPending && !query.data) {
    return (
      <AppText style={{ color: colors.muted }} variant="caption">
        Loading upcoming Bills...
      </AppText>
    );
  }
  if (query.isError && !query.data) {
    return (
      <View style={styles.inlineState}>
        <AppText style={{ color: colors.danger }} variant="caption">
          {displayError(query.error, settings.currencyCode, 'Upcoming Bills could not be loaded.')}
        </AppText>
        <Button
          accessibilityLabel="Retry upcoming recurring Bills"
          icon={RefreshCw}
          label="Retry"
          onPress={() => void query.refetch()}
          variant="secondary"
        />
      </View>
    );
  }
  return query.isError && query.data ? <StaleBanner /> : null;
}

function AttentionRow({ item, onPress }: { item: DashboardAttentionItem; onPress: () => void }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  return (
    <Pressable
      accessibilityHint="Opens the relevant record"
      accessibilityLabel={`${item.name}, ${attentionDescription(item, settings.currencyCode)}`}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
    >
      <View style={styles.rowText}>
        <AppText numberOfLines={2} variant="label">
          {item.name}
        </AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          {attentionDescription(item, settings.currencyCode)}
        </AppText>
      </View>
      <ChevronRight color={colors.muted} size={18} />
    </Pressable>
  );
}

function ActiveSummary({
  data,
  onOpen,
}: {
  data: DashboardSummary;
  onOpen: (paycheckId: string) => void;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const active = data.active;
  return (
    <View style={styles.group}>
      <View style={styles.metrics} testID="home-active-metrics">
        <Metric label="Active" value={String(active.paycheckCount)} />
        <Metric
          label="Unallocated"
          value={formatMoney(active.totalUnallocatedMinor, settings.currencyCode)}
        />
        <Metric label="Not Paid" value={String(active.notPaidEntryCount)} />
        <Metric label="Processing" value={String(active.processingEntryCount)} />
      </View>
      {active.previews.length ? (
        <View style={styles.rows}>
          {active.previews.map((preview) => (
            <PaycheckPreviewRow
              key={preview.paycheckId}
              onPress={() => onOpen(preview.paycheckId)}
              preview={preview}
            />
          ))}
        </View>
      ) : (
        <AppText style={{ color: colors.muted }} variant="caption">
          No Active paychecks.
        </AppText>
      )}
    </View>
  );
}

function PaycheckPreviewRow({
  onPress,
  preview,
}: {
  onPress: () => void;
  preview: DashboardPaycheckPreview;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const pendingCount = preview.notPaidCount + preview.processingCount;
  return (
    <Pressable
      accessibilityLabel={`Open ${preview.name} paycheck`}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
    >
      <View style={styles.rowText}>
        <AppText numberOfLines={2} variant="label">
          {preview.name}
        </AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          {formatMoney(preview.unallocatedMinor, settings.currencyCode)} unallocated ·{' '}
          {pendingCount} not Posted
        </AppText>
      </View>
      <ChevronRight color={colors.muted} size={18} />
    </Pressable>
  );
}

function RecurringBillRow({ item }: { item: RecurringBillOccurrence }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  return (
    <View
      accessibilityLabel={`${item.name}, due ${formatDate(item.occurrenceDate)}`}
      style={styles.row}
    >
      <View style={styles.rowText}>
        <AppText numberOfLines={2} variant="label">
          {item.name}
        </AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          {formatDate(item.occurrenceDate)} ·{' '}
          {item.paymentMethod === 'MANUAL' ? 'Manual Pay' : 'Autopay'}
        </AppText>
      </View>
      <AppText style={styles.amount} variant="caption">
        {formatMoney(item.typicalAmountMinor, settings.currencyCode)}
      </AppText>
    </View>
  );
}

function FinancialPositions({
  data,
  onExpenseLists,
  onPaybacks,
  onPlannedSavings,
}: {
  data: DashboardSummary;
  onExpenseLists: () => void;
  onPaybacks: () => void;
  onPlannedSavings: () => void;
}) {
  const { settings } = useSettings();
  return (
    <View style={styles.rows} testID="home-financial-positions">
      <PositionRow
        accessibilityLabel="Open Paybacks"
        label="Paybacks"
        onPress={onPaybacks}
        value={`${formatMoney(data.paybacks.totalRemainingMinor, settings.currencyCode)} · ${data.paybacks.activeCount} active`}
      />
      <PositionRow
        accessibilityLabel="Open Planned Savings"
        label="Planned Savings"
        onPress={onPlannedSavings}
        value={`${formatMoney(data.plannedSavings.totalActiveReservedBalanceMinor, settings.currencyCode)} · ${data.plannedSavings.activeCount} active`}
      />
      <PositionRow
        accessibilityLabel="Open Expense Lists"
        label="Expense Lists"
        onPress={onExpenseLists}
        value={`${data.expenseLists.openCount} Open · ${data.expenseLists.finalizedCount} Finalized${data.expenseLists.finalizedCount ? ' · ready to settle' : ''}`}
      />
    </View>
  );
}

function PositionRow({
  accessibilityLabel,
  label,
  onPress,
  value,
}: {
  accessibilityLabel: string;
  label: string;
  onPress: () => void;
  value: string;
}) {
  const { colors } = useAppTheme();
  return (
    <Pressable
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [styles.row, pressed && styles.pressed]}
    >
      <View style={styles.rowText}>
        <AppText variant="label">{label}</AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          {value}
        </AppText>
      </View>
      <ChevronRight color={colors.muted} size={18} />
    </Pressable>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.metric}>
      <AppText style={{ color: colors.muted }} variant="caption">
        {label}
      </AppText>
      <AppText numberOfLines={2} variant="label">
        {value}
      </AppText>
    </View>
  );
}

function openAttentionItem(router: ReturnType<typeof useRouter>, item: DashboardAttentionItem) {
  if (item.kind === 'FINALIZED_EXPENSE_LEDGER' && item.expenseLedgerId) {
    router.push(`/expense-ledgers/${item.expenseLedgerId}`);
    return;
  }
  if (!item.paycheckId) return;
  if (item.kind === 'UNALLOCATED_PAYCHECK' || !item.entryId) {
    router.push(`/paychecks/${item.paycheckId}`);
    return;
  }
  router.push({
    pathname: '/paychecks/[id]',
    params: { highlightEntryId: item.entryId, id: item.paycheckId },
  });
}

function attentionDescription(item: DashboardAttentionItem, currencyCode: string) {
  const amount = formatMoney(item.amountMinor, currencyCode);
  switch (item.kind) {
    case 'MANUAL_BILL_NOT_PAID':
      return `Manual Pay${item.dueDate ? ` · Due ${formatDate(item.dueDate)}` : ''} · ${amount}`;
    case 'UNALLOCATED_PAYCHECK':
      return `${amount} unallocated`;
    case 'PROCESSING_ENTRY':
      return `Processing since ${formatDate(item.attentionSinceDate ?? '')} · ${amount}`;
    case 'OVER_BUDGET_BUCKET':
      return `${amount} over budget`;
    case 'FINALIZED_EXPENSE_LEDGER':
      return `${amount} ready to settle`;
  }
}

function formatDate(value: string) {
  if (!value) return 'an earlier date';
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(
    new Date(`${value}T00:00:00`),
  );
}

const styles = StyleSheet.create({
  amount: { flexShrink: 0, fontWeight: '700', textAlign: 'right' },
  content: { gap: 0, padding: 16, paddingBottom: 36 },
  group: { gap: 12 },
  header: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 12,
    justifyContent: 'space-between',
    paddingBottom: 10,
  },
  inlineState: { alignItems: 'flex-start', gap: 9 },
  metric: { flexBasis: '45%', flexGrow: 1, gap: 3, minWidth: 0 },
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  pressed: { opacity: 0.72 },
  row: { alignItems: 'center', flexDirection: 'row', gap: 10, minHeight: 52, paddingVertical: 7 },
  rows: { gap: 2 },
  rowText: { flex: 1, gap: 2, minWidth: 0 },
  section: { borderTopWidth: 1, gap: 12, paddingVertical: 17 },
  sectionAction: { alignItems: 'center', flexDirection: 'row', minHeight: 44, paddingLeft: 8 },
  sectionHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    justifyContent: 'space-between',
  },
  sectionTitle: { fontSize: 16, lineHeight: 22 },
  titleBlock: { flexGrow: 1, flexShrink: 1, gap: 3, minWidth: 180 },
});
