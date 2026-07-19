import { zodResolver } from '@hookform/resolvers/zod';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ArrowLeft, Archive, Pencil, RotateCcw, Save, WalletCards, X } from 'lucide-react-native';
import { useEffect, useMemo, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { z } from 'zod';

import type { SinkingFund, SinkingFundTransaction } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { IconButton } from '@/components/icon-button';
import { ProgressBar } from '@/components/progress-bar';
import { Screen } from '@/components/screen';
import { ErrorState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney, parseMoneyToMinor } from '@/domain/money';
import { SinkingFundEditor } from '@/features/sinking-funds/sinking-fund-editor';
import { today } from '@/features/paychecks/form-schemas';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const SINKING_FUND_TRANSACTION_PAGE_SIZE = 100;

export default function SinkingFundDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [editing, setEditing] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const [archiveConfirming, setArchiveConfirming] = useState(false);
  const [reversingTransaction, setReversingTransaction] = useState<SinkingFundTransaction | null>(
    null,
  );
  const fundQuery = useQuery({
    queryKey: ['sinking-fund', id],
    queryFn: () => api.sinkingFund(id),
    enabled: Boolean(id),
  });
  const transactionsQuery = useInfiniteQuery({
    queryKey: ['sinking-fund', id, 'transactions'],
    queryFn: ({ pageParam }) =>
      api.sinkingFundTransactions(id, pageParam, SINKING_FUND_TRANSACTION_PAGE_SIZE),
    enabled: Boolean(id),
    getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.page + 1 : undefined),
    initialPageParam: 0,
  });
  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['sinking-funds'] }),
      queryClient.invalidateQueries({ queryKey: ['sinking-fund', id] }),
      queryClient.invalidateQueries({ queryKey: ['sinking-fund', id, 'transactions'] }),
      queryClient.invalidateQueries({ queryKey: ['paycheck'] }),
      queryClient.invalidateQueries({ queryKey: ['paychecks'] }),
    ]);
  };
  const updateMutation = useMutation({
    mutationFn: (values: {
      name: string;
      notes: string | null;
      targetDate: string | null;
      targetMinor: number | null;
    }) => {
      if (!fundQuery.data) throw new Error('Refresh Planned Savings before editing.');
      return api.updateSinkingFund(id, { ...values, version: fundQuery.data.version });
    },
    onSuccess: async () => {
      await invalidate();
      setEditing(false);
    },
  });
  const lifecycleMutation = useMutation({
    mutationFn: (values: { action: 'archive' | 'restore'; confirmPositiveBalance?: boolean }) => {
      if (!fundQuery.data) throw new Error('Refresh Planned Savings first.');
      if (values.action === 'restore') return api.restoreSinkingFund(id, fundQuery.data.version);
      return api.archiveSinkingFund(id, fundQuery.data.version, values.confirmPositiveBalance);
    },
    onSuccess: async () => {
      await invalidate();
      setArchiveConfirming(false);
    },
  });
  const withdrawalMutation = useMutation({
    mutationFn: (values: {
      amountMinor: number;
      effectiveDate: string;
      notes: string | null;
      reason: string;
    }) => {
      if (!fundQuery.data) throw new Error('Refresh Planned Savings before withdrawing.');
      return api.withdrawSinkingFund(id, { ...values, version: fundQuery.data.version });
    },
    onSuccess: async () => {
      await invalidate();
      setWithdrawing(false);
    },
  });
  const reverseMutation = useMutation({
    mutationFn: (values: { reason: string; transaction: SinkingFundTransaction }) =>
      api.reverseSinkingFundWithdrawal(values.transaction.id, {
        reason: values.reason,
        version: values.transaction.version,
      }),
    onSuccess: async () => {
      await invalidate();
      setReversingTransaction(null);
    },
  });
  const transactions = useMemo(() => {
    const seen = new Set<string>();
    const rows: SinkingFundTransaction[] = [];
    for (const page of transactionsQuery.data?.pages ?? []) {
      for (const transaction of page.items) {
        if (!seen.has(transaction.id)) {
          seen.add(transaction.id);
          rows.push(transaction);
        }
      }
    }
    return rows;
  }, [transactionsQuery.data]);
  const totalTransactions = transactionsQuery.data?.pages[0]?.totalItems ?? 0;

  if (editing && fundQuery.data) {
    return (
      <SinkingFundEditor
        onClose={() => setEditing(false)}
        onSubmit={(values) => updateMutation.mutateAsync(values).then(() => undefined)}
        sinkingFund={fundQuery.data}
      />
    );
  }

  return (
    <Screen>
      <Stack.Screen
        options={{ headerShown: true, title: fundQuery.data?.name ?? 'Planned Savings' }}
      />
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <IconButton icon={ArrowLeft} label="Back" onPress={() => router.back()} />
          {fundQuery.data ? (
            <View style={styles.headerActions}>
              <Button
                icon={Pencil}
                label="Edit"
                onPress={() => setEditing(true)}
                variant="secondary"
              />
              {fundQuery.data.state === 'ACTIVE' ? (
                <>
                  <Button
                    icon={WalletCards}
                    label="Withdraw"
                    onPress={() => setWithdrawing(true)}
                    variant="secondary"
                  />
                  <Button
                    icon={Archive}
                    label={
                      fundQuery.data.currentBalanceMinor > 0 ? 'Archive with balance' : 'Archive'
                    }
                    loading={lifecycleMutation.isPending}
                    onPress={() => {
                      if (fundQuery.data.currentBalanceMinor > 0) {
                        setArchiveConfirming(true);
                      } else {
                        lifecycleMutation.mutate({
                          action: 'archive',
                          confirmPositiveBalance: false,
                        });
                      }
                    }}
                    variant="ghost"
                  />
                </>
              ) : (
                <Button
                  icon={RotateCcw}
                  label="Restore"
                  loading={lifecycleMutation.isPending}
                  onPress={() => lifecycleMutation.mutate({ action: 'restore' })}
                />
              )}
            </View>
          ) : null}
        </View>
        {lifecycleMutation.error ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {displayError(
              lifecycleMutation.error,
              settings.currencyCode,
              'Planned Savings state was not changed.',
            )}
          </AppText>
        ) : null}
        {reverseMutation.error ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {displayError(
              reverseMutation.error,
              settings.currencyCode,
              'The withdrawal was not reversed.',
            )}
          </AppText>
        ) : null}
        {fundQuery.isPending ? (
          <ActivityIndicator color={colors.accent} size="large" style={styles.loader} />
        ) : fundQuery.isError || !fundQuery.data ? (
          <ErrorState
            message={displayError(
              fundQuery.error,
              settings.currencyCode,
              'Check the API connection and try again.',
            )}
            retry={() => fundQuery.refetch()}
          />
        ) : (
          <SinkingFundDetail sinkingFund={fundQuery.data} />
        )}
        {fundQuery.data ? (
          <View style={styles.section}>
            <AppText variant="label">Transactions</AppText>
            {transactionsQuery.isPending ? (
              <ActivityIndicator color={colors.accent} />
            ) : transactions.length ? (
              <>
                <AppText style={{ color: colors.muted }} variant="caption">
                  Showing {transactions.length} of {totalTransactions} transactions
                </AppText>
                {transactions.map((transaction) => (
                  <TransactionRow
                    key={transaction.id}
                    onReverse={() => setReversingTransaction(transaction)}
                    reversing={
                      reverseMutation.isPending && reversingTransaction?.id === transaction.id
                    }
                    transaction={transaction}
                  />
                ))}
                {transactionsQuery.hasNextPage ? (
                  <Button
                    label="Load older transactions"
                    loading={transactionsQuery.isFetchingNextPage}
                    onPress={() => transactionsQuery.fetchNextPage()}
                    variant="secondary"
                  />
                ) : null}
              </>
            ) : null}
            {transactions.length === 0 &&
            !transactionsQuery.isPending &&
            !transactionsQuery.isError ? (
              <AppText style={{ color: colors.muted }} variant="caption">
                No transactions yet.
              </AppText>
            ) : null}
            {transactionsQuery.isError ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {displayError(
                  transactionsQuery.error,
                  settings.currencyCode,
                  'Transaction history could not be loaded.',
                )}
              </AppText>
            ) : null}
          </View>
        ) : null}
      </ScrollView>
      <WithdrawalSheet
        error={withdrawalMutation.error}
        loading={withdrawalMutation.isPending}
        onClose={() => setWithdrawing(false)}
        onSubmit={(values) => withdrawalMutation.mutateAsync(values).then(() => undefined)}
        visible={withdrawing}
      />
      <ArchiveConfirmation
        error={lifecycleMutation.error}
        loading={lifecycleMutation.isPending}
        onCancel={() => setArchiveConfirming(false)}
        onConfirm={() =>
          lifecycleMutation.mutate({ action: 'archive', confirmPositiveBalance: true })
        }
        sinkingFund={fundQuery.data ?? null}
        visible={archiveConfirming}
      />
      <ReverseWithdrawalSheet
        error={reverseMutation.error}
        loading={reverseMutation.isPending}
        onClose={() => setReversingTransaction(null)}
        onSubmit={(reason) =>
          reverseMutation
            .mutateAsync({ reason, transaction: reversingTransaction! })
            .then(() => undefined)
        }
        transaction={reversingTransaction}
      />
    </Screen>
  );
}

function SinkingFundDetail({ sinkingFund }: { sinkingFund: SinkingFund }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const progress =
    sinkingFund.progressPercent == null ? null : Math.round(sinkingFund.progressPercent);
  return (
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.titleRow}>
        <View style={styles.titleBlock}>
          <AppText variant="title">{sinkingFund.name}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {sinkingFund.state === 'ACTIVE' ? 'Active' : 'Archived'}
            {sinkingFund.targetDate ? ` | Target ${formatDate(sinkingFund.targetDate)}` : ''}
          </AppText>
        </View>
      </View>
      <Metric
        label="Current balance"
        value={formatMoney(sinkingFund.currentBalanceMinor, settings.currencyCode)}
      />
      {sinkingFund.targetMinor == null ? null : (
        <View style={styles.metrics}>
          <Metric
            label="Target"
            value={formatMoney(sinkingFund.targetMinor, settings.currencyCode)}
          />
          <Metric
            label="Remaining"
            value={formatMoney(sinkingFund.remainingTargetMinor ?? 0, settings.currencyCode)}
          />
        </View>
      )}
      {sinkingFund.progressPercent == null ? null : (
        <ProgressBar
          accessibilityLabel={`${progress ?? 0} percent funded`}
          tone="posted"
          value={sinkingFund.progressPercent}
        />
      )}
      {sinkingFund.notes ? (
        <AppText style={{ color: colors.muted }} variant="body">
          {sinkingFund.notes}
        </AppText>
      ) : null}
    </View>
  );
}

function TransactionRow({
  onReverse,
  reversing,
  transaction,
}: {
  onReverse: () => void;
  reversing: boolean;
  transaction: SinkingFundTransaction;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const signedAmount =
    transaction.transactionType === 'WITHDRAWAL'
      ? -transaction.amountMinor
      : transaction.amountMinor;
  return (
    <View
      style={[styles.historyRow, { backgroundColor: colors.surface, borderColor: colors.border }]}
    >
      <View style={styles.historyHeader}>
        <View style={styles.titleBlock}>
          <AppText variant="label">{transactionTitle(transaction)}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {formatDate(transaction.effectiveDate)}
          </AppText>
        </View>
        <AppText variant="label">{formatMoney(signedAmount, settings.currencyCode)}</AppText>
      </View>
      {transaction.paycheckName ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          {transaction.paycheckName}
          {transaction.entryName ? ` | ${transaction.entryName}` : ''}
        </AppText>
      ) : null}
      {transaction.reversedAt ? (
        <>
          <AppText style={{ color: colors.muted }} variant="caption">
            Reversed {formatDateTime(transaction.reversedAt)}
          </AppText>
          {transaction.reversalReason ? (
            <AppText style={{ color: colors.muted }} variant="caption">
              Reversal reason: {transaction.reversalReason}
            </AppText>
          ) : null}
        </>
      ) : transaction.transactionType === 'WITHDRAWAL' ? (
        <Button
          icon={RotateCcw}
          label="Reverse withdrawal"
          loading={reversing}
          onPress={onReverse}
          variant="secondary"
        />
      ) : null}
    </View>
  );
}

function ArchiveConfirmation({
  error,
  loading,
  onCancel,
  onConfirm,
  sinkingFund,
  visible,
}: {
  error: Error | null;
  loading: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  sinkingFund: SinkingFund | null;
  visible: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  if (!sinkingFund) return null;
  return (
    <Modal animationType="fade" onRequestClose={onCancel} transparent visible={visible}>
      <View style={styles.dialogBackdrop}>
        <View
          style={[styles.dialog, { backgroundColor: colors.surface, borderColor: colors.border }]}
        >
          <AppText variant="title">Archive with balance</AppText>
          <AppText style={{ color: colors.muted }} variant="body">
            {formatMoney(sinkingFund.currentBalanceMinor, settings.currencyCode)} will remain in the
            archived Planned Savings history.
          </AppText>
          {error ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {displayError(error, settings.currencyCode, 'Planned Savings state was not changed.')}
            </AppText>
          ) : null}
          <View style={styles.formActions}>
            <Button label="Cancel" onPress={onCancel} variant="ghost" />
            <Button icon={Archive} label="Archive" loading={loading} onPress={onConfirm} />
          </View>
        </View>
      </View>
    </Modal>
  );
}

const reversalSchema = z.object({
  reason: z.string().trim().min(1, 'Enter a reversal reason.').max(1000),
});
type ReversalValues = z.infer<typeof reversalSchema>;

function ReverseWithdrawalSheet({
  error,
  loading,
  onClose,
  onSubmit,
  transaction,
}: {
  error: Error | null;
  loading: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<void>;
  transaction: SinkingFundTransaction | null;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const {
    control,
    formState: { errors },
    handleSubmit,
    reset,
    setError,
  } = useForm<ReversalValues>({
    resolver: zodResolver(reversalSchema),
    defaultValues: { reason: '' },
  });

  useEffect(() => {
    if (transaction) reset({ reason: '' });
  }, [reset, transaction]);

  async function submit(values: ReversalValues) {
    try {
      await onSubmit(values.reason.trim());
      reset({ reason: '' });
    } catch (submitError) {
      setError('root', {
        message: displayError(
          submitError,
          settings.currencyCode,
          'The withdrawal was not reversed.',
        ),
      });
    }
  }

  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={Boolean(transaction)}>
      <View style={[styles.sheetScreen, { backgroundColor: colors.background }]}>
        <View style={[styles.sheetHeader, { borderBottomColor: colors.border }]}>
          <View>
            <AppText variant="title">Reverse withdrawal</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {transaction ? formatMoney(transaction.amountMinor, settings.currencyCode) : ''}
            </AppText>
          </View>
          <Pressable
            accessibilityLabel="Close reversal form"
            onPress={onClose}
            style={styles.close}
          >
            <X color={colors.text} size={23} />
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={styles.form} keyboardShouldPersistTaps="handled">
          <Controller
            control={control}
            name="reason"
            render={({ field }) => (
              <TextField
                error={errors.reason?.message}
                label="Reason"
                multiline
                onBlur={field.onBlur}
                onChangeText={field.onChange}
                value={String(field.value ?? '')}
              />
            )}
          />
          {errors.root?.message || error ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {errors.root?.message ??
                displayError(error, settings.currencyCode, 'The withdrawal was not reversed.')}
            </AppText>
          ) : null}
          <View style={styles.formActions}>
            <Button label="Cancel" onPress={onClose} variant="ghost" />
            <Button
              icon={RotateCcw}
              label="Reverse withdrawal"
              loading={loading}
              onPress={handleSubmit(submit)}
              variant="danger"
            />
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}

const withdrawalSchema = z.object({
  amount: z.string().min(1, 'Enter an amount.'),
  effectiveDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.'),
  reason: z.string().trim().min(1, 'Enter a reason.').max(500),
  notes: z.string().max(2000),
});
type WithdrawalValues = z.infer<typeof withdrawalSchema>;

function WithdrawalSheet({
  error,
  loading,
  onClose,
  onSubmit,
  visible,
}: {
  error: Error | null;
  loading: boolean;
  onClose: () => void;
  onSubmit: (values: {
    amountMinor: number;
    effectiveDate: string;
    notes: string | null;
    reason: string;
  }) => Promise<void>;
  visible: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const {
    control,
    formState: { errors },
    handleSubmit,
    reset,
    setError,
  } = useForm<WithdrawalValues>({
    resolver: zodResolver(withdrawalSchema),
    defaultValues: { amount: '', effectiveDate: today(), reason: '', notes: '' },
  });

  async function submit(values: WithdrawalValues) {
    try {
      await onSubmit({
        amountMinor: parseMoneyToMinor(values.amount),
        effectiveDate: values.effectiveDate,
        reason: values.reason.trim(),
        notes: values.notes.trim() || null,
      });
      reset({ amount: '', effectiveDate: today(), reason: '', notes: '' });
    } catch (submitError) {
      setError('root', {
        message: displayError(submitError, settings.currencyCode, 'The withdrawal was not saved.'),
      });
    }
  }

  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={visible}>
      <View style={[styles.sheetScreen, { backgroundColor: colors.background }]}>
        <View style={[styles.sheetHeader, { borderBottomColor: colors.border }]}>
          <View>
            <AppText variant="title">Withdraw</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              Record money leaving Planned Savings.
            </AppText>
          </View>
          <Pressable
            accessibilityLabel="Close withdrawal form"
            onPress={onClose}
            style={styles.close}
          >
            <X color={colors.text} size={23} />
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={styles.form} keyboardShouldPersistTaps="handled">
          <WithdrawalField
            control={control}
            error={errors.amount?.message}
            keyboardType="decimal-pad"
            label="Amount"
            name="amount"
          />
          <WithdrawalField
            control={control}
            error={errors.effectiveDate?.message}
            label="Date"
            name="effectiveDate"
            placeholder="YYYY-MM-DD"
          />
          <WithdrawalField
            control={control}
            error={errors.reason?.message}
            label="Reason"
            name="reason"
          />
          <WithdrawalField control={control} label="Notes (optional)" multiline name="notes" />
          {errors.root?.message || error ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {errors.root?.message ??
                displayError(error, settings.currencyCode, 'The withdrawal was not saved.')}
            </AppText>
          ) : null}
          <Button
            icon={Save}
            label="Save withdrawal"
            loading={loading}
            onPress={handleSubmit(submit)}
          />
        </ScrollView>
      </View>
    </Modal>
  );
}

function WithdrawalField({
  control,
  error,
  keyboardType,
  label,
  multiline,
  name,
  placeholder,
}: {
  control: ReturnType<typeof useForm<WithdrawalValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad';
  label: string;
  multiline?: boolean;
  name: keyof WithdrawalValues;
  placeholder?: string;
}) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => (
        <TextField
          error={error}
          keyboardType={keyboardType}
          label={label}
          multiline={multiline}
          onBlur={field.onBlur}
          onChangeText={field.onChange}
          placeholder={placeholder}
          value={String(field.value ?? '')}
        />
      )}
    />
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.metric}>
      <AppText style={{ color: colors.muted }} variant="caption">
        {label}
      </AppText>
      <AppText variant="label">{value}</AppText>
    </View>
  );
}

function transactionTitle(transaction: SinkingFundTransaction) {
  if (transaction.transactionType === 'CONTRIBUTION') return 'Contribution';
  if (transaction.transactionType === 'OPENING_BALANCE') return 'Opening balance';
  return transaction.reason ?? 'Withdrawal';
}

function formatDate(value: string) {
  return new Date(`${value}T00:00:00`).toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

const styles = StyleSheet.create({
  card: { borderRadius: 8, borderWidth: 1, gap: 13, padding: 15 },
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  content: { gap: 16, padding: 16, paddingBottom: 32 },
  dialog: { borderRadius: 8, borderWidth: 1, gap: 13, margin: 18, padding: 18 },
  dialogBackdrop: {
    backgroundColor: 'rgba(0,0,0,0.45)',
    flex: 1,
    justifyContent: 'center',
  },
  form: { gap: 13, padding: 18, paddingBottom: 40 },
  formActions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  header: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  headerActions: { alignItems: 'center', flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  historyHeader: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
  },
  historyRow: { borderRadius: 8, borderWidth: 1, gap: 9, padding: 13 },
  loader: { marginTop: 48 },
  metric: { flex: 1, gap: 3 },
  metrics: { flexDirection: 'row', gap: 12 },
  section: { gap: 10 },
  sheetHeader: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 18,
  },
  sheetScreen: { flex: 1 },
  titleBlock: { flex: 1, gap: 3 },
  titleRow: { alignItems: 'flex-start', flexDirection: 'row', justifyContent: 'space-between' },
});
