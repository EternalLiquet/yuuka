import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { Archive, CheckCircle2, Copy, Pencil, Plus, RotateCcw } from 'lucide-react-native';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { RefreshControl, StyleSheet, View } from 'react-native';
import DraggableFlatList, { RenderItemParams } from 'react-native-draggable-flatlist';

import type {
  Entry,
  EntryPaymentMethod,
  EntryStatus,
  EntryType,
  Paycheck,
  SpendingBucketPerformanceSummary,
} from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { EntryPayload, useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ProgressBar } from '@/components/progress-bar';
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
import { BucketTransactionSheet } from '@/features/paychecks/bucket-transaction-sheet';
import { EntryEditor } from '@/features/paychecks/entry-editor';
import { filterAndSortEntries, EntrySort } from '@/features/paychecks/entry-list';
import { EntryRow } from '@/features/paychecks/entry-row';
import { PaycheckEditor } from '@/features/paychecks/paycheck-editor';
import { StatusSheet } from '@/features/paychecks/status-sheet';
import { StatusHistorySheet } from '@/features/paychecks/status-history-sheet';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const statusOptions = [
  { label: 'All statuses', value: 'ALL' },
  { label: 'Not Paid', value: 'NOT_PAID' },
  { label: 'Processing', value: 'PROCESSING' },
  { label: 'Posted', value: 'POSTED' },
] as const;
const typeOptions = [
  { label: 'All types', value: 'ALL' },
  { label: 'Bills', value: 'BILL' },
  { label: 'Buckets', value: 'SPENDING_BUCKET' },
  { label: 'Funds', value: 'SINKING_FUND' },
] as const;
const paymentMethodOptions = [
  { label: 'All payment methods', value: 'ALL' },
  { label: 'Manual Pay', value: 'MANUAL' },
  { label: 'Autopay', value: 'AUTOPAY' },
] as const;
const sortOptions = [
  { label: 'Custom', value: 'custom' },
  { label: 'Amount', value: 'amount' },
  { label: 'Status', value: 'status' },
  { label: 'Due date', value: 'due-date' },
  { label: 'Last edited', value: 'last-edited' },
] as const;

type EntryListHandle = {
  scrollToIndex: (params: { animated?: boolean; index: number; viewPosition?: number }) => void;
};
type ScrollToIndexFailureInfo = {
  averageItemLength: number;
  highestMeasuredFrameIndex: number;
  index: number;
};

const HIGHLIGHT_SCROLL_RETRY_DELAY_MS = 80;
const MAX_HIGHLIGHT_SCROLL_RETRIES = 2;

export default function PaycheckDetailScreen() {
  const { highlightEntryId, id } = useLocalSearchParams<{
    highlightEntryId?: string;
    id: string;
  }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [statusFilter, setStatusFilter] = useState<'ALL' | EntryStatus>('ALL');
  const [typeFilter, setTypeFilter] = useState<'ALL' | EntryType>('ALL');
  const [paymentMethodFilter, setPaymentMethodFilter] = useState<'ALL' | EntryPaymentMethod>('ALL');
  const [sort, setSort] = useState<EntrySort>('custom');
  const [direction, setDirection] = useState<'asc' | 'desc'>('asc');
  const [editingEntry, setEditingEntry] = useState<Entry | null>(null);
  const [entryEditorVisible, setEntryEditorVisible] = useState(false);
  const [statusEntry, setStatusEntry] = useState<Entry | null>(null);
  const [historyEntry, setHistoryEntry] = useState<Entry | null>(null);
  const [bucketEntryId, setBucketEntryId] = useState<string | null>(null);
  const [detailError, setDetailError] = useState('');
  const [paycheckEditorVisible, setPaycheckEditorVisible] = useState(false);
  const leftoverInFlight = useRef(false);
  const listRef = useRef<EntryListHandle | null>(null);
  const highlightScrolledRef = useRef(false);
  const highlightRetryCountRef = useRef(0);
  const highlightRetryTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const userInteractedWithListRef = useRef(false);
  const query = useQuery({ queryKey: ['paycheck', id], queryFn: () => api.paycheck(id) });
  const paybacksQuery = useQuery({
    queryKey: ['paybacks', 'entry-editor'],
    queryFn: api.paybacks,
    enabled: entryEditorVisible,
  });
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 1000);
  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['paycheck', id] }),
      queryClient.invalidateQueries({ queryKey: ['paychecks'] }),
      queryClient.invalidateQueries({ queryKey: ['paybacks'] }),
      queryClient.invalidateQueries({ queryKey: ['payback'] }),
      queryClient.invalidateQueries({ queryKey: ['spending-buckets'] }),
    ]);
  };

  const statusMutation = useMutation({
    mutationFn: ({
      entry,
      effectiveAt,
      note,
      toStatus,
    }: {
      entry: Entry;
      effectiveAt: string;
      note: string;
      toStatus: EntryStatus;
    }) => api.changeStatus(entry.id, { effectiveAt, note, toStatus, version: entry.version }),
    onSuccess: invalidate,
  });
  const entryMutation = useMutation({
    mutationFn: async (action: {
      entry?: Entry | null;
      payload?: EntryPayload;
      type: 'add' | 'delete' | 'update';
    }) => {
      if (action.type === 'delete' && action.entry)
        return api.deleteEntry(action.entry.id, action.entry.version);
      if (action.type === 'update' && action.entry && action.payload)
        return api.updateEntry(action.entry.id, {
          ...action.payload,
          version: action.entry.version,
        });
      if (action.payload) return api.addEntry(id, action.payload);
      throw new Error('Entry action is incomplete.');
    },
    onSuccess: async () => {
      setDetailError('');
      await invalidate();
    },
  });
  const leftoverMutation = useMutation({
    mutationFn: () => {
      if (!query.data) throw new Error('Refresh the paycheck before allocating leftover.');
      if (query.data.unallocatedMinor <= 0) {
        throw new Error('There is no leftover money to allocate.');
      }
      return api.allocateLeftover(id, query.data.version);
    },
    onSuccess: async () => {
      setDetailError('');
      await invalidate();
    },
    onError: async (error) => {
      setDetailError(displayError(error, settings.currencyCode));
      await query.refetch();
    },
    onSettled: () => {
      leftoverInFlight.current = false;
    },
  });
  const reorderMutation = useMutation({
    mutationFn: (entryIds: string[]) => {
      if (!query.data) throw new Error('Refresh the paycheck before reordering.');
      return api.reorderEntries(id, entryIds, query.data.version);
    },
    onSuccess: invalidate,
  });
  const lifecycleMutation = useMutation({
    mutationFn: (action: 'archive' | 'close' | 'reopen') => {
      if (!query.data) throw new Error('Refresh the paycheck first.');
      if (action === 'close') return api.closePaycheck(id, query.data.version);
      if (action === 'reopen') return api.reopenPaycheck(id, query.data.version);
      return api.archivePaycheck(id, query.data.version);
    },
    onSuccess: invalidate,
  });
  const paycheckMutation = useMutation({
    mutationFn: (values: {
      amountMinor: number;
      incomeDate: string;
      name: string;
      notes: string | null;
      source: string | null;
      version: number;
    }) => api.updatePaycheck(id, values),
    onSuccess: invalidate,
  });

  const displayedEntries = useMemo(
    () =>
      filterAndSortEntries(query.data?.entries ?? [], {
        sort,
        direction,
        paymentMethod: paymentMethodFilter === 'ALL' ? undefined : paymentMethodFilter,
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        type: typeFilter === 'ALL' ? undefined : typeFilter,
      }),
    [direction, paymentMethodFilter, query.data?.entries, sort, statusFilter, typeFilter],
  );
  const selectedBucketEntry = useMemo(
    () => query.data?.entries.find((entry) => entry.id === bucketEntryId) ?? null,
    [bucketEntryId, query.data?.entries],
  );
  const canReorder =
    sort === 'custom' &&
    statusFilter === 'ALL' &&
    typeFilter === 'ALL' &&
    paymentMethodFilter === 'ALL' &&
    query.data?.state === 'ACTIVE';

  const markListInteracted = useCallback(() => {
    userInteractedWithListRef.current = true;
    if (highlightRetryTimeoutRef.current) {
      clearTimeout(highlightRetryTimeoutRef.current);
      highlightRetryTimeoutRef.current = null;
    }
  }, []);

  const scheduleHighlightRetry = useCallback((index: number) => {
    if (userInteractedWithListRef.current || highlightRetryTimeoutRef.current) {
      return;
    }
    if (highlightRetryCountRef.current >= MAX_HIGHLIGHT_SCROLL_RETRIES) {
      return;
    }
    highlightRetryCountRef.current += 1;
    highlightRetryTimeoutRef.current = setTimeout(() => {
      highlightRetryTimeoutRef.current = null;
      if (userInteractedWithListRef.current || !listRef.current) {
        return;
      }
      try {
        listRef.current.scrollToIndex({ animated: true, index, viewPosition: 0.35 });
      } catch {
        // Native list measurement can still be catching up; onScrollToIndexFailed bounds retries.
      }
    }, HIGHLIGHT_SCROLL_RETRY_DELAY_MS);
  }, []);

  const handleScrollToIndexFailed = useCallback(
    (info: ScrollToIndexFailureInfo) => {
      if (!highlightEntryId || userInteractedWithListRef.current) {
        return;
      }
      const highlightIndex = displayedEntries.findIndex((entry) => entry.id === highlightEntryId);
      if (highlightIndex < 0 || info.index !== highlightIndex) {
        return;
      }
      scheduleHighlightRetry(info.index);
    },
    [displayedEntries, highlightEntryId, scheduleHighlightRetry],
  );

  useEffect(() => {
    highlightScrolledRef.current = false;
    highlightRetryCountRef.current = 0;
    userInteractedWithListRef.current = false;
    if (highlightRetryTimeoutRef.current) {
      clearTimeout(highlightRetryTimeoutRef.current);
      highlightRetryTimeoutRef.current = null;
    }
    return () => {
      if (highlightRetryTimeoutRef.current) {
        clearTimeout(highlightRetryTimeoutRef.current);
        highlightRetryTimeoutRef.current = null;
      }
    };
  }, [highlightEntryId, id]);

  useEffect(() => {
    if (
      showColdLoader ||
      !highlightEntryId ||
      highlightScrolledRef.current ||
      userInteractedWithListRef.current
    ) {
      return;
    }
    if (!listRef.current) {
      return;
    }
    const index = displayedEntries.findIndex((entry) => entry.id === highlightEntryId);
    if (index < 0) {
      return;
    }
    try {
      listRef.current?.scrollToIndex({ animated: true, index, viewPosition: 0.35 });
      highlightScrolledRef.current = true;
    } catch {
      scheduleHighlightRetry(index);
      highlightScrolledRef.current = true;
    }
  }, [displayedEntries, highlightEntryId, scheduleHighlightRetry, showColdLoader]);

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading paycheck..." />
      </Screen>
    );
  }
  if (query.isError && !query.data) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <ErrorState
          message={displayError(
            query.error,
            settings.currencyCode,
            'The request could not be completed.',
          )}
          retry={() => query.refetch()}
        />
      </Screen>
    );
  }
  const paycheck = query.data;
  if (!paycheck) return null;

  function allocateLeftover() {
    if (leftoverInFlight.current || leftoverMutation.isPending) return;
    leftoverInFlight.current = true;
    leftoverMutation.mutate();
  }

  function reorder(entryIds: string[]) {
    if (!canReorder) return;
    reorderMutation.mutate(entryIds);
  }

  function moveEntry(index: number, offset: number) {
    const ids = displayedEntries.map((entry) => entry.id);
    const target = index + offset;
    if (target < 0 || target >= ids.length) return;
    [ids[index], ids[target]] = [ids[target], ids[index]];
    reorder(ids);
  }

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: paycheck.name,
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <Screen>
        <DraggableFlatList
          contentContainerStyle={styles.list}
          data={displayedEntries}
          keyExtractor={(entry) => entry.id}
          onDragBegin={markListInteracted}
          ListEmptyComponent={
            <EmptyState
              mascot="clipboard"
              message="Adjust the active filters or add an entry."
              title="No entries shown"
            />
          }
          ListHeaderComponent={
            <DetailHeader
              paycheck={paycheck}
              refreshing={query.isFetching && Boolean(query.data)}
              stale={query.isError}
              onAdd={() => {
                setEditingEntry(null);
                setEntryEditorVisible(true);
              }}
              onEdit={() => setPaycheckEditorVisible(true)}
              onDuplicate={() => router.push(`/paychecks/duplicate/${id}`)}
              statusFilter={statusFilter}
              setStatusFilter={setStatusFilter}
              typeFilter={typeFilter}
              setTypeFilter={setTypeFilter}
              paymentMethodFilter={paymentMethodFilter}
              setPaymentMethodFilter={setPaymentMethodFilter}
              sort={sort}
              setSort={setSort}
              direction={direction}
              setDirection={setDirection}
              detailError={detailError}
              lifecycleMutation={lifecycleMutation}
              leftoverMutation={leftoverMutation}
              onAllocateLeftover={allocateLeftover}
            />
          }
          onDragEnd={({ data }) => reorder(data.map((entry) => entry.id))}
          onScrollBeginDrag={markListInteracted}
          onScrollToIndexFailed={handleScrollToIndexFailed}
          ref={(instance) => {
            listRef.current = instance;
          }}
          refreshControl={
            <RefreshControl
              colors={['transparent']}
              onRefresh={() => void query.refetch()}
              progressBackgroundColor="transparent"
              refreshing={query.isRefetching}
              tintColor="transparent"
            />
          }
          renderItem={(params: RenderItemParams<Entry>) => {
            const index = displayedEntries.findIndex((entry) => entry.id === params.item.id);
            return (
              <EntryRow
                drag={params.drag}
                entry={params.item}
                isFirst={index === 0}
                isLast={index === displayedEntries.length - 1}
                position={index + 1}
                onEdit={() => {
                  setEditingEntry(params.item);
                  setEntryEditorVisible(true);
                }}
                onMoveDown={() => moveEntry(index, 1)}
                onMoveUp={() => moveEntry(index, -1)}
                onHistory={() => setHistoryEntry(params.item)}
                onBucketActivity={
                  params.item.entryType === 'SPENDING_BUCKET'
                    ? () => setBucketEntryId(params.item.id)
                    : undefined
                }
                onStatusPress={() => setStatusEntry(params.item)}
                highlighted={params.item.id === highlightEntryId}
                reorderEnabled={canReorder}
              />
            );
          }}
        />
      </Screen>

      <BucketTransactionSheet
        entry={selectedBucketEntry}
        onChanged={invalidate}
        onClose={() => setBucketEntryId(null)}
      />
      <StatusSheet
        entry={statusEntry}
        onClose={() => setStatusEntry(null)}
        onSubmit={async (values) => {
          if (!statusEntry) return;
          await statusMutation.mutateAsync({ entry: statusEntry, ...values });
        }}
        visible={Boolean(statusEntry)}
      />
      <StatusHistorySheet entry={historyEntry} onClose={() => setHistoryEntry(null)} />
      <EntryEditor
        entry={editingEntry}
        onClose={() => setEntryEditorVisible(false)}
        onDelete={
          editingEntry
            ? () =>
                entryMutation
                  .mutateAsync({ type: 'delete', entry: editingEntry })
                  .then(() => undefined)
            : undefined
        }
        onRetryPaybacks={() => paybacksQuery.refetch()}
        onSubmit={(payload) =>
          entryMutation
            .mutateAsync({ type: editingEntry ? 'update' : 'add', entry: editingEntry, payload })
            .then(() => undefined)
        }
        paybacks={paybacksQuery.data?.items ?? []}
        paybacksError={
          paybacksQuery.isError
            ? displayError(
                paybacksQuery.error,
                settings.currencyCode,
                'Paybacks could not be loaded.',
              )
            : null
        }
        paybacksLoading={paybacksQuery.isPending}
        visible={entryEditorVisible}
      />
      <PaycheckEditor
        onClose={() => setPaycheckEditorVisible(false)}
        onSubmit={(values) => paycheckMutation.mutateAsync(values).then(() => undefined)}
        paycheck={paycheck}
        visible={paycheckEditorVisible}
      />
    </>
  );
}

function DetailHeader({
  direction,
  detailError,
  lifecycleMutation,
  leftoverMutation,
  onAdd,
  onAllocateLeftover,
  onEdit,
  onDuplicate,
  paymentMethodFilter,
  paycheck,
  refreshing,
  setDirection,
  setPaymentMethodFilter,
  setSort,
  setStatusFilter,
  setTypeFilter,
  sort,
  stale,
  statusFilter,
  typeFilter,
}: {
  detailError: string;
  direction: 'asc' | 'desc';
  lifecycleMutation: UseMutationResult<Paycheck, Error, 'archive' | 'close' | 'reopen'>;
  leftoverMutation: UseMutationResult<Entry, Error, void>;
  onAdd: () => void;
  onAllocateLeftover: () => void;
  onEdit: () => void;
  onDuplicate: () => void;
  paymentMethodFilter: 'ALL' | EntryPaymentMethod;
  paycheck: Paycheck;
  refreshing: boolean;
  setDirection: (value: 'asc' | 'desc') => void;
  setPaymentMethodFilter: (value: 'ALL' | EntryPaymentMethod) => void;
  setSort: (value: EntrySort) => void;
  setStatusFilter: (value: 'ALL' | EntryStatus) => void;
  setTypeFilter: (value: 'ALL' | EntryType) => void;
  sort: EntrySort;
  stale: boolean;
  statusFilter: 'ALL' | EntryStatus;
  typeFilter: 'ALL' | EntryType;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const entryCount = paycheck.postedCount + paycheck.processingCount + paycheck.notPaidCount;
  const complete =
    entryCount > 0 &&
    paycheck.unallocatedMinor === 0 &&
    paycheck.processingCount === 0 &&
    paycheck.notPaidCount === 0 &&
    paycheck.postedMinor === paycheck.allocatedMinor;
  const nonDefault =
    statusFilter !== 'ALL' ||
    typeFilter !== 'ALL' ||
    paymentMethodFilter !== 'ALL' ||
    sort !== 'custom';
  return (
    <View style={styles.header}>
      <YuukaRefreshIndicator visible={refreshing} />
      {stale ? <StaleBanner /> : null}
      <View style={styles.paycheckHeading}>
        <View style={styles.paycheckTitle}>
          <AppText variant="title">{paycheck.name}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {formatDate(paycheck.incomeDate)}
            {paycheck.source ? `  |  ${paycheck.source}` : ''}
          </AppText>
        </View>
        <AppText variant="money">
          {formatMoney(paycheck.amountMinor, settings.currencyCode)}
        </AppText>
      </View>
      <View style={styles.metrics}>
        <Metric
          label="Allocated"
          value={formatMoney(paycheck.allocatedMinor, settings.currencyCode)}
        />
        <Metric
          label="Unallocated"
          tone={paycheck.unallocatedMinor === 0 ? colors.posted : colors.processing}
          value={formatMoney(paycheck.unallocatedMinor, settings.currencyCode)}
        />
        <Metric label="Posted" value={formatMoney(paycheck.postedMinor, settings.currencyCode)} />
      </View>
      {paycheck.spendingBucketPerformance ? (
        <SpendingBucketPerformanceCard summary={paycheck.spendingBucketPerformance} />
      ) : null}
      <View style={styles.progressBlock}>
        <AppText style={{ color: colors.muted }} variant="caption">
          Allocation {paycheck.allocationPercent.toFixed(0)}%
        </AppText>
        <ProgressBar accessibilityLabel="Allocation progress" value={paycheck.allocationPercent} />
        <AppText style={{ color: colors.muted }} variant="caption">
          Completion {paycheck.completionPercent.toFixed(0)}%
        </AppText>
        <ProgressBar
          accessibilityLabel="Completion progress"
          tone="posted"
          value={paycheck.completionPercent}
        />
      </View>
      <View style={styles.lifecycleActions}>
        {paycheck.state === 'ACTIVE' ? (
          <>
            <Button icon={Pencil} label="Edit paycheck" onPress={onEdit} variant="secondary" />
            <Button
              icon={Copy}
              label="Duplicate Paycheck"
              onPress={onDuplicate}
              variant="secondary"
            />
            <Button icon={Plus} label="Add entry" onPress={onAdd} />
            {paycheck.unallocatedMinor > 0 ? (
              <Button
                disabled={leftoverMutation.isPending}
                icon={Plus}
                label="Add LEFTOVER"
                loading={leftoverMutation.isPending}
                onPress={onAllocateLeftover}
                variant="secondary"
              />
            ) : null}
            {complete ? (
              <Button
                icon={CheckCircle2}
                label="Close paycheck"
                loading={lifecycleMutation.isPending}
                onPress={() => lifecycleMutation.mutate('close')}
                variant="secondary"
              />
            ) : null}
            <Button
              icon={Archive}
              label="Archive"
              onPress={() => lifecycleMutation.mutate('archive')}
              variant="ghost"
            />
          </>
        ) : (
          <>
            <Button
              icon={RotateCcw}
              label="Reopen paycheck"
              loading={lifecycleMutation.isPending}
              onPress={() => lifecycleMutation.mutate('reopen')}
            />
            <Button
              icon={Copy}
              label="Duplicate Paycheck"
              onPress={onDuplicate}
              variant="secondary"
            />
          </>
        )}
      </View>
      {lifecycleMutation.error ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {displayError(lifecycleMutation.error, settings.currencyCode)}
        </AppText>
      ) : null}
      {detailError ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {detailError}
        </AppText>
      ) : null}
      <View style={[styles.controls, { borderTopColor: colors.border }]}>
        <View style={styles.controlHeading}>
          <AppText variant="label">Entries</AppText>
          {nonDefault ? (
            <AppText style={{ color: colors.accent }} variant="caption">
              Filtered or sorted
            </AppText>
          ) : null}
        </View>
        <SegmentedControl
          label="Status filter"
          onChange={setStatusFilter}
          options={statusOptions}
          value={statusFilter}
        />
        <SegmentedControl
          label="Type filter"
          onChange={setTypeFilter}
          options={typeOptions}
          value={typeFilter}
        />
        <SegmentedControl
          label="Payment method filter"
          onChange={setPaymentMethodFilter}
          options={paymentMethodOptions}
          value={paymentMethodFilter}
        />
        <SegmentedControl
          label="Entry sort"
          onChange={setSort}
          options={sortOptions}
          value={sort}
        />
        {sort !== 'custom' ? (
          <SegmentedControl
            label="Sort direction"
            onChange={setDirection}
            options={[
              { label: 'Ascending', value: 'asc' },
              { label: 'Descending', value: 'desc' },
            ]}
            value={direction}
          />
        ) : null}
      </View>
    </View>
  );
}

function SpendingBucketPerformanceCard({ summary }: { summary: SpendingBucketPerformanceSummary }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const net = netDescription(summary.netMinor, settings.currencyCode);
  return (
    <View
      accessibilityLabel={`Spending bucket performance: ${net}`}
      style={[
        styles.bucketSummary,
        { backgroundColor: colors.surfaceElevated, borderColor: colors.border },
      ]}
    >
      <View style={styles.bucketSummaryHeader}>
        <AppText variant="label">Spending Buckets</AppText>
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
        <Metric
          label="Spent to date"
          value={formatMoney(summary.spentMinor, settings.currencyCode)}
        />
      </View>
    </View>
  );
}

function Metric({ label, tone, value }: { label: string; tone?: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.metric}>
      <AppText style={{ color: colors.muted }} variant="caption">
        {label}
      </AppText>
      <AppText style={{ color: tone ?? colors.text, fontWeight: '700' }} variant="caption">
        {value}
      </AppText>
    </View>
  );
}

function netDescription(netMinor: number, currencyCode: string) {
  if (netMinor > 0) return `Under by ${formatMoney(netMinor, currencyCode)}`;
  if (netMinor < 0) return `Over by ${formatMoney(Math.abs(netMinor), currencyCode)}`;
  return 'Exactly on budget';
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'long',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${value}T12:00:00`));
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
  bucketSummary: { borderRadius: 8, borderWidth: 1, gap: 10, padding: 12 },
  bucketSummaryHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'space-between',
  },
  controlHeading: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  controls: { borderTopWidth: 1, gap: 10, paddingTop: 16 },
  header: { gap: 16, paddingBottom: 6 },
  lifecycleActions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  list: { flexGrow: 1, padding: 16 },
  metric: { flex: 1, gap: 3 },
  metrics: { flexDirection: 'row', gap: 8 },
  paycheckHeading: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
  },
  paycheckTitle: { flex: 1, gap: 3 },
  progressBlock: { gap: 6 },
});
