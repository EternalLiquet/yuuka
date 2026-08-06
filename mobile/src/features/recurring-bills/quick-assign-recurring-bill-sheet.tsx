import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Pencil, X } from 'lucide-react-native';
import { useMemo, useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { Paycheck, RecurringBillOccurrence } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ErrorState, StaleBanner, YuukaLoadingState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export function QuickAssignRecurringBillSheet({
  occurrence,
  onClose,
  onCreatePaycheck,
  onCreated,
}: {
  occurrence: RecurringBillOccurrence | null;
  onClose: () => void;
  onCreatePaycheck: () => void;
  onCreated: (paycheckId: string, entryId: string) => void;
}) {
  const api = useYuukaApi();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [amountMinor, setAmountMinor] = useState(occurrence?.typicalAmountMinor ?? 0);
  const [amountInput, setAmountInput] = useState('');
  const [editingAmount, setEditingAmount] = useState(false);
  const [updateTypicalAmount, setUpdateTypicalAmount] = useState(false);
  const [amountError, setAmountError] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const submission = useRef(false);
  const paychecks = useQuery({
    queryKey: ['paychecks', 'active'],
    queryFn: api.activePaychecks,
    enabled: Boolean(occurrence),
  });

  const selected = useMemo(
    () => paychecks.data?.items.find((item) => item.id === selectedId) ?? null,
    [paychecks.data?.items, selectedId],
  );
  const canFit = Boolean(selected && selected.unallocatedMinor >= amountMinor);

  function beginAmountEdit() {
    if (saving) return;
    setAmountInput(minorToInput(amountMinor));
    setAmountError('');
    setEditingAmount(true);
  }

  function applyAmount(updateTypical: boolean) {
    try {
      setAmountMinor(parseMoneyToMinor(amountInput));
      setUpdateTypicalAmount(updateTypical);
      setEditingAmount(false);
      setAmountError('');
    } catch (valueError) {
      setAmountError(valueError instanceof Error ? valueError.message : 'Enter a valid amount.');
    }
  }

  async function confirm() {
    if (!occurrence || !selected || !canFit || submission.current) return;
    submission.current = true;
    setSaving(true);
    setError('');
    const priorEntryIds = new Set(selected.entries.map((entry) => entry.id));
    try {
      const result = await api.importRecurringBills(selected.id, selected.version, [
        {
          amountMinor,
          definitionId: occurrence.definitionId,
          definitionVersion: occurrence.definitionVersion,
          occurrenceDate: occurrence.occurrenceDate,
          updateTypicalAmount,
        },
      ]);
      const created = result.entries.find(
        (entry) =>
          !priorEntryIds.has(entry.id) &&
          entry.sourceRecurringBillDefinitionId === occurrence.definitionId &&
          entry.sourceRecurringOccurrenceDate === occurrence.occurrenceDate,
      );
      if (!created)
        throw new Error('The imported Bill could not be identified. Refresh and try again.');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['dashboard', 'upcoming-recurring-bills'] }),
        queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'timeline'] }),
        queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'import-options'] }),
        queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'definitions'] }),
        queryClient.invalidateQueries({ queryKey: ['paychecks', 'active'] }),
        queryClient.invalidateQueries({ queryKey: ['paycheck', selected.id] }),
        queryClient.invalidateQueries({ queryKey: ['dashboard', 'summary'] }),
      ]);
      onCreated(selected.id, created.id);
    } catch (importError) {
      setError(
        displayError(importError, settings.currencyCode, 'The recurring Bill was not added.'),
      );
      await Promise.allSettled([
        paychecks.refetch(),
        queryClient.invalidateQueries({ queryKey: ['dashboard', 'upcoming-recurring-bills'] }),
        queryClient.invalidateQueries({ queryKey: ['recurring-bills'] }),
      ]);
    } finally {
      submission.current = false;
      setSaving(false);
    }
  }

  return (
    <>
      <Modal
        animationType="slide"
        onRequestClose={() => {
          if (!saving) onClose();
        }}
        testID="quick-assignment-modal"
        visible={Boolean(occurrence)}
      >
        <View style={[styles.screen, { backgroundColor: colors.background }]}>
          <View style={[styles.header, { borderBottomColor: colors.border }]}>
            <View style={styles.grow}>
              <AppText variant="title">Add recurring Bill</AppText>
              <AppText style={{ color: colors.muted }} variant="caption">
                Choose where this occurrence belongs.
              </AppText>
            </View>
            <Pressable
              accessibilityLabel="Close quick assignment"
              accessibilityRole="button"
              accessibilityState={{ disabled: saving }}
              disabled={saving}
              onPress={onClose}
              style={styles.close}
            >
              <X color={colors.text} size={23} />
            </Pressable>
          </View>
          <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
            {occurrence ? (
              <OccurrenceReview
                item={occurrence}
                amountMinor={amountMinor}
                disabled={saving}
                onEditAmount={beginAmountEdit}
              />
            ) : null}
            <AppText variant="label">Active paycheck</AppText>
            {paychecks.isPending && !paychecks.data ? (
              <YuukaLoadingState message="Loading Active paychecks..." />
            ) : null}
            {paychecks.isError && !paychecks.data ? (
              <ErrorState
                message={displayError(
                  paychecks.error,
                  settings.currencyCode,
                  'Active paychecks could not load.',
                )}
                retry={() => paychecks.refetch()}
              />
            ) : null}
            {paychecks.isError && paychecks.data ? <StaleBanner /> : null}
            {paychecks.data?.items.length === 0 ? (
              <View style={styles.empty}>
                <AppText>No Active paychecks are available.</AppText>
                <Button
                  disabled={saving}
                  label="Create Paycheck"
                  onPress={onCreatePaycheck}
                  variant="secondary"
                />
              </View>
            ) : null}
            {paychecks.data?.items.map((paycheck) => (
              <PaycheckChoice
                key={paycheck.id}
                amountMinor={amountMinor}
                interactionsDisabled={saving}
                onSelect={() => setSelectedId(paycheck.id)}
                paycheck={paycheck}
                selected={selectedId === paycheck.id}
              />
            ))}
            {paychecks.data?.items.length &&
            paychecks.data.items.some((item) => item.unallocatedMinor >= amountMinor) ? (
              <Button
                disabled={saving}
                label="Create paycheck instead"
                onPress={onCreatePaycheck}
                variant="ghost"
              />
            ) : null}
            {paychecks.data?.items.length &&
            !paychecks.data.items.some((item) => item.unallocatedMinor >= amountMinor) ? (
              <View style={styles.empty}>
                <AppText style={{ color: colors.danger }} variant="caption">
                  No Active paycheck has enough unallocated money. Create a paycheck or adjust the
                  amount.
                </AppText>
                <Button
                  disabled={saving}
                  label="Create Paycheck"
                  onPress={onCreatePaycheck}
                  variant="secondary"
                />
              </View>
            ) : null}
            {error ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {error}
              </AppText>
            ) : null}
            <Button
              disabled={!selected || !canFit}
              icon={Check}
              label="Confirm import"
              loading={saving}
              onPress={confirm}
            />
          </ScrollView>
        </View>
      </Modal>
      <Modal
        animationType="fade"
        onRequestClose={() => setEditingAmount(false)}
        transparent
        visible={editingAmount}
      >
        <View style={styles.backdrop}>
          <View style={[styles.dialog, { backgroundColor: colors.surface }]}>
            <AppText variant="title">{occurrence?.name}</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              Typical amount:{' '}
              {formatMoney(occurrence?.typicalAmountMinor ?? 0, settings.currencyCode)}
            </AppText>
            <TextField
              keyboardType="decimal-pad"
              label="Amount for this paycheck"
              onChangeText={setAmountInput}
              value={amountInput}
            />
            {amountError ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {amountError}
              </AppText>
            ) : null}
            <AppText variant="label">Update the recurring Bill&apos;s typical amount?</AppText>
            <Button label="This paycheck only" onPress={() => applyAmount(false)} />
            <Button
              label="Update typical amount"
              onPress={() => applyAmount(true)}
              variant="secondary"
            />
            <Button label="Cancel" onPress={() => setEditingAmount(false)} variant="ghost" />
          </View>
        </View>
      </Modal>
    </>
  );
}

function OccurrenceReview({
  item,
  amountMinor,
  disabled,
  onEditAmount,
}: {
  item: RecurringBillOccurrence;
  amountMinor: number;
  disabled: boolean;
  onEditAmount: () => void;
}) {
  const { settings } = useSettings();
  const { colors } = useAppTheme();
  return (
    <View style={[styles.review, { borderColor: colors.border }]}>
      <View style={styles.reviewHeading}>
        <AppText variant="label">{item.name}</AppText>
        <AppText variant="money">{formatMoney(amountMinor, settings.currencyCode)}</AppText>
      </View>
      <AppText style={{ color: colors.muted }} variant="caption">
        Due {formatDate(item.occurrenceDate)} ·{' '}
        {item.paymentMethod === 'MANUAL' ? 'Manual' : 'Autopay'}
      </AppText>
      <AppText style={{ color: colors.muted }} variant="caption">
        Account: {item.accountName ?? 'None'} · Payee: {item.payee ?? 'None'}
      </AppText>
      <AppText style={{ color: colors.muted }} variant="caption">
        Notes: {item.notes ?? 'None'}
      </AppText>
      <Button
        disabled={disabled}
        icon={Pencil}
        label="Edit amount"
        onPress={onEditAmount}
        variant="secondary"
      />
    </View>
  );
}

function PaycheckChoice({
  amountMinor,
  interactionsDisabled,
  onSelect,
  paycheck,
  selected,
}: {
  amountMinor: number;
  interactionsDisabled: boolean;
  onSelect: () => void;
  paycheck: Paycheck;
  selected: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const shortfall = Math.max(0, amountMinor - paycheck.unallocatedMinor);
  const disabled = shortfall > 0 || interactionsDisabled;
  const insufficient = shortfall > 0;
  const resulting = paycheck.unallocatedMinor - amountMinor;
  return (
    <Pressable
      accessibilityLabel={`${paycheck.name}, income date ${formatDate(paycheck.incomeDate)}, ${formatMoney(paycheck.unallocatedMinor, settings.currencyCode)} currently unallocated, ${insufficient ? `short by ${formatMoney(shortfall, settings.currencyCode)}, unavailable` : `${formatMoney(resulting, settings.currencyCode)} unallocated after import`}`}
      accessibilityRole="radio"
      accessibilityState={{ checked: selected, disabled }}
      disabled={disabled}
      onPress={onSelect}
      style={[
        styles.choice,
        { borderColor: selected ? colors.accent : colors.border },
        disabled && styles.disabled,
      ]}
    >
      <AppText variant="label">{paycheck.name}</AppText>
      <AppText style={{ color: colors.muted }} variant="caption">
        Income {formatDate(paycheck.incomeDate)}
      </AppText>
      <AppText variant="caption">
        Current: {formatMoney(paycheck.unallocatedMinor, settings.currencyCode)} · Result:{' '}
        {insufficient ? 'Cannot fit' : formatMoney(resulting, settings.currencyCode)}
      </AppText>
      {insufficient ? (
        <AppText style={{ color: colors.danger }} variant="caption">
          Short by {formatMoney(shortfall, settings.currencyCode)}
        </AppText>
      ) : null}
    </Pressable>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${value}T00:00:00`));
}

const styles = StyleSheet.create({
  backdrop: { backgroundColor: 'rgba(0,0,0,0.6)', flex: 1, justifyContent: 'center', padding: 24 },
  choice: { borderRadius: 8, borderWidth: 1, gap: 4, minHeight: 72, padding: 12 },
  close: { alignItems: 'center', justifyContent: 'center', minHeight: 44, minWidth: 44 },
  content: { gap: 14, padding: 18, paddingBottom: 36 },
  dialog: { borderRadius: 10, gap: 14, padding: 18 },
  disabled: { opacity: 0.58 },
  empty: { gap: 10 },
  grow: { flex: 1, gap: 3, minWidth: 0 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 12,
    padding: 16,
  },
  review: { borderRadius: 8, borderWidth: 1, gap: 7, padding: 13 },
  reviewHeading: {
    alignItems: 'center',
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    justifyContent: 'space-between',
  },
  screen: { flex: 1 },
});
