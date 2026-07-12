import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseMutationResult } from '@tanstack/react-query';
import { Stack, useLocalSearchParams } from 'expo-router';
import { Archive, CheckCircle2, Pencil, Plus, RotateCcw } from 'lucide-react-native';
import { useMemo, useRef, useState } from 'react';
import { ActivityIndicator, RefreshControl, StyleSheet, View } from 'react-native';
import DraggableFlatList, { RenderItemParams } from 'react-native-draggable-flatlist';

import type { Entry, EntryStatus, EntryType, Paycheck } from '@/api/contracts';
import { EntryPayload, useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ProgressBar } from '@/components/progress-bar';
import { Screen } from '@/components/screen';
import { SegmentedControl } from '@/components/segmented-control';
import { EmptyState, ErrorState, StaleBanner } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { BucketTransactionSheet } from '@/features/paychecks/bucket-transaction-sheet';
import { EntryEditor } from '@/features/paychecks/entry-editor';
import { filterAndSortEntries, EntrySort } from '@/features/paychecks/entry-list';
import { EntryRow } from '@/features/paychecks/entry-row';
import { PaycheckEditor } from '@/features/paychecks/paycheck-editor';
import { StatusSheet } from '@/features/paychecks/status-sheet';
import { StatusHistorySheet } from '@/features/paychecks/status-history-sheet';
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
const sortOptions = [
  { label: 'Custom', value: 'custom' },
  { label: 'Amount', value: 'amount' },
  { label: 'Status', value: 'status' },
  { label: 'Due date', value: 'due-date' },
  { label: 'Last edited', value: 'last-edited' },
] as const;

export default function PaycheckDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const [statusFilter, setStatusFilter] = useState<'ALL' | EntryStatus>('ALL');
  const [typeFilter, setTypeFilter] = useState<'ALL' | EntryType>('ALL');
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
  const query = useQuery({ queryKey: ['paycheck', id], queryFn: () => api.paycheck(id) });
  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['paycheck', id] }),
      queryClient.invalidateQueries({ queryKey: ['paychecks'] }),
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
      setDetailError(message(error));
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
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        type: typeFilter === 'ALL' ? undefined : typeFilter,
      }),
    [direction, query.data?.entries, sort, statusFilter, typeFilter],
  );
  const selectedBucketEntry = useMemo(
    () => query.data?.entries.find((entry) => entry.id === bucketEntryId) ?? null,
    [bucketEntryId, query.data?.entries],
  );
  const canReorder =
    sort === 'custom' &&
    statusFilter === 'ALL' &&
    typeFilter === 'ALL' &&
    query.data?.state === 'ACTIVE';

  if (query.isPending && !query.data) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <ActivityIndicator color={colors.accent} size="large" />
      </Screen>
    );
  }
  if (query.isError && !query.data) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <ErrorState message={message(query.error)} retry={() => query.refetch()} />
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
          ListEmptyComponent={
            <EmptyState
              message="Adjust the active filters or add an entry."
              title="No entries shown"
            />
          }
          ListHeaderComponent={
            <DetailHeader
              paycheck={paycheck}
              stale={query.isError}
              onAdd={() => {
                setEditingEntry(null);
                setEntryEditorVisible(true);
              }}
              onEdit={() => setPaycheckEditorVisible(true)}
              statusFilter={statusFilter}
              setStatusFilter={setStatusFilter}
              typeFilter={typeFilter}
              setTypeFilter={setTypeFilter}
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
          refreshControl={
            <RefreshControl
              colors={[colors.accent]}
              onRefresh={() => query.refetch()}
              refreshing={query.isRefetching}
              tintColor={colors.accent}
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
        onSubmit={(payload) =>
          entryMutation
            .mutateAsync({ type: editingEntry ? 'update' : 'add', entry: editingEntry, payload })
            .then(() => undefined)
        }
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
  paycheck,
  setDirection,
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
  paycheck: Paycheck;
  setDirection: (value: 'asc' | 'desc') => void;
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
  const nonDefault = statusFilter !== 'ALL' || typeFilter !== 'ALL' || sort !== 'custom';
  return (
    <View style={styles.header}>
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
          <Button
            icon={RotateCcw}
            label="Reopen paycheck"
            loading={lifecycleMutation.isPending}
            onPress={() => lifecycleMutation.mutate('reopen')}
          />
        )}
      </View>
      {lifecycleMutation.error ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {message(lifecycleMutation.error)}
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

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'long',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${value}T12:00:00`));
}

function message(error: unknown) {
  return error instanceof Error ? error.message : 'The request could not be completed.';
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
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
