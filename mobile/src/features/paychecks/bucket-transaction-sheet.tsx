import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Save, Trash2, X } from 'lucide-react-native';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { BucketTransaction, Entry } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { IconButton } from '@/components/icon-button';
import { TextField } from '@/components/text-field';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

import { today } from './form-schemas';

export function BucketTransactionSheet({
  entry,
  onChanged,
  onClose,
}: {
  entry: Entry | null;
  onChanged: () => Promise<void>;
  onClose: () => void;
}) {
  const api = useYuukaApi();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [editing, setEditing] = useState<BucketTransaction | null>(null);
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [notes, setNotes] = useState('');
  const [effectiveDate, setEffectiveDate] = useState(today());
  const [error, setError] = useState('');
  const query = useQuery({
    queryKey: ['entry', entry?.id, 'bucket-transactions'],
    queryFn: () => api.bucketTransactions(entry!.id),
    enabled: Boolean(entry),
  });
  const mutation = useMutation({
    mutationFn: async (action: 'delete' | 'save') => {
      if (!entry) throw new Error('Choose a bucket.');
      if (action === 'delete' && editing)
        return api.deleteBucketTransaction(editing.id, editing.version);

      const amountMinor = parseMoneyToMinor(amount);
      if (amountMinor <= 0) throw new Error('Enter a purchase amount greater than zero.');
      if (!/^\d{4}-\d{2}-\d{2}$/.test(effectiveDate)) throw new Error('Use YYYY-MM-DD.');
      const values = {
        amountMinor,
        description: description.trim(),
        notes: notes.trim(),
        effectiveDate,
      };
      return editing
        ? api.updateBucketTransaction(editing.id, { ...values, version: editing.version })
        : api.addBucketTransaction(entry.id, values);
    },
    onSuccess: async () => {
      resetForm();
      await queryClient.invalidateQueries({
        queryKey: ['entry', entry?.id, 'bucket-transactions'],
      });
      await onChanged();
    },
    onError: (mutationError) =>
      setError(displayError(mutationError, settings.currencyCode, 'The purchase was not saved.')),
  });

  useEffect(() => {
    if (entry) resetForm();
  }, [entry]);

  function edit(transaction: BucketTransaction) {
    setEditing(transaction);
    setAmount(minorToInput(transaction.amountMinor));
    setDescription(transaction.description ?? '');
    setNotes(transaction.notes ?? '');
    setEffectiveDate(transaction.effectiveDate);
    setError('');
  }

  function resetForm() {
    setEditing(null);
    setAmount('');
    setDescription('');
    setNotes('');
    setEffectiveDate(today());
    setError('');
  }

  const spentMinor = entry?.spentMinor ?? 0;
  const remainingMinor = entry?.remainingMinor ?? entry?.amountMinor ?? 0;
  const overBudget = Boolean(entry?.overBudget);

  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={Boolean(entry)}>
      <View style={[styles.screen, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <View style={styles.headerText}>
            <AppText variant="title">Bucket ledger</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {entry?.name}
            </AppText>
          </View>
          <Pressable
            accessibilityLabel="Close bucket ledger"
            accessibilityRole="button"
            onPress={onClose}
            style={styles.close}
          >
            <X color={colors.text} size={22} />
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <View style={styles.metrics}>
            <Metric
              label="Budgeted"
              value={formatMoney(entry?.amountMinor ?? 0, settings.currencyCode)}
            />
            <Metric label="Spent" value={formatMoney(spentMinor, settings.currencyCode)} />
            <Metric
              label={overBudget ? 'Over budget' : 'Remaining'}
              tone={overBudget ? colors.danger : colors.posted}
              value={formatMoney(Math.abs(remainingMinor), settings.currencyCode)}
            />
          </View>

          <View
            style={[styles.form, { backgroundColor: colors.surface, borderColor: colors.border }]}
          >
            <AppText variant="label">{editing ? 'Edit purchase' : 'Add purchase'}</AppText>
            <TextField
              keyboardType="decimal-pad"
              label="Amount"
              onChangeText={setAmount}
              placeholder="12.35"
              value={amount}
            />
            <TextField
              label="Transaction date"
              onChangeText={setEffectiveDate}
              placeholder="YYYY-MM-DD"
              value={effectiveDate}
            />
            <TextField
              label="Merchant or description (optional)"
              onChangeText={setDescription}
              value={description}
            />
            <TextField label="Notes (optional)" multiline onChangeText={setNotes} value={notes} />
            {error ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {error}
              </AppText>
            ) : null}
            <View style={styles.formActions}>
              <Button
                icon={editing ? Save : Plus}
                label={editing ? 'Save purchase' : 'Add purchase'}
                loading={mutation.isPending}
                onPress={() => mutation.mutate('save')}
              />
              {editing ? <Button label="Cancel" onPress={resetForm} variant="ghost" /> : null}
              {editing ? (
                <Button
                  icon={Trash2}
                  label="Delete"
                  loading={mutation.isPending}
                  onPress={() => mutation.mutate('delete')}
                  variant="danger"
                />
              ) : null}
            </View>
          </View>

          <View style={styles.list}>
            <AppText variant="label">Purchases</AppText>
            {query.isPending ? <ActivityIndicator color={colors.accent} /> : null}
            {query.data?.items.map((transaction) => (
              <View
                key={transaction.id}
                style={[styles.transaction, { borderBottomColor: colors.border }]}
              >
                <View style={styles.transactionText}>
                  <AppText variant="money">
                    {formatMoney(transaction.amountMinor, settings.currencyCode)}
                  </AppText>
                  <AppText style={{ color: colors.muted }} variant="caption">
                    {formatDate(transaction.effectiveDate)}
                    {transaction.description ? ` | ${transaction.description}` : ''}
                  </AppText>
                  {transaction.notes ? (
                    <AppText style={{ color: colors.muted }} variant="caption">
                      {transaction.notes}
                    </AppText>
                  ) : null}
                </View>
                <IconButton
                  icon={Pencil}
                  label={`Edit ${formatMoney(transaction.amountMinor, settings.currencyCode)} purchase`}
                  onPress={() => edit(transaction)}
                />
              </View>
            ))}
            {query.data?.items.length === 0 ? (
              <AppText style={{ color: colors.muted }} variant="caption">
                No purchases recorded.
              </AppText>
            ) : null}
            {query.isError ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {displayError(query.error, settings.currencyCode, 'Purchases could not be loaded.')}
              </AppText>
            ) : null}
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}

function Metric({ label, tone, value }: { label: string; tone?: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={[styles.metric, { backgroundColor: colors.surface, borderColor: colors.border }]}>
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
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${value}T12:00:00`));
}

const styles = StyleSheet.create({
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  content: { gap: 20, padding: 18, paddingBottom: 36 },
  form: { borderRadius: 8, borderWidth: 1, gap: 14, padding: 15 },
  formActions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 18,
  },
  headerText: { flex: 1, gap: 3 },
  list: { gap: 10 },
  metric: { borderRadius: 8, borderWidth: 1, flex: 1, gap: 4, padding: 12 },
  metrics: { flexDirection: 'row', gap: 8 },
  screen: { flex: 1 },
  transaction: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 10,
    paddingVertical: 11,
  },
  transactionText: { flex: 1, gap: 4 },
});
