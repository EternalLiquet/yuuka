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

type ImportAttempt = {
  amountMinor: number;
  definitionId: string;
  occurrenceDate: string;
  paycheckId: string;
  priorEntryIds: Set<string>;
};

export function QuickAssignRecurringBillSheet({
  assignmentIdentity,
  occurrence,
  occurrenceResolved,
  onClose,
  onCreatePaycheck,
  onCreated,
  onOpenImport,
  onReviewImports,
  onViewTimeline,
  refreshOccurrence,
}: {
  assignmentIdentity: { definitionId: string; occurrenceDate: string } | null;
  occurrence: RecurringBillOccurrence | null;
  occurrenceResolved: boolean;
  onClose: () => void;
  onCreatePaycheck: () => void;
  onCreated: (paycheckId: string, entryId: string) => void;
  onOpenImport: (paycheckId: string, entryId: string) => void;
  onReviewImports: (occurrence: RecurringBillOccurrence) => void;
  onViewTimeline: () => void;
  refreshOccurrence: () => Promise<RecurringBillOccurrence | null>;
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
  const [progress, setProgress] = useState<'idle' | 'importing' | 'checking'>('idle');
  const [completed, setCompleted] = useState(false);
  const [outcomeUnknown, setOutcomeUnknown] = useState(false);
  const [authoritativePaycheck, setAuthoritativePaycheck] = useState<Paycheck | null>(null);
  const [lastOccurrence, setLastOccurrence] = useState(occurrence);
  const [recoveryOccurrence, setRecoveryOccurrence] = useState<RecurringBillOccurrence | null>(
    null,
  );
  const [occurrenceUnavailable, setOccurrenceUnavailable] = useState(false);
  const originalImportError = useRef<unknown>(null);
  const submission = useRef(false);
  const unresolvedAttempt = useRef<ImportAttempt | null>(null);
  const paychecks = useQuery({
    queryKey: ['paychecks', 'active'],
    queryFn: api.activePaychecks,
    enabled: Boolean(assignmentIdentity),
  });
  const effectivePaychecks = useMemo(() => {
    const items = paychecks.data?.items ?? [];
    if (!authoritativePaycheck) return items;
    const replaced = items.map((item) =>
      item.id === authoritativePaycheck.id ? authoritativePaycheck : item,
    );
    return replaced.some((item) => item.id === authoritativePaycheck.id)
      ? replaced
      : [...replaced, authoritativePaycheck];
  }, [authoritativePaycheck, paychecks.data?.items]);
  const selected = effectivePaychecks.find((item) => item.id === selectedId) ?? null;
  const canFit = Boolean(
    selected && selected.state === 'ACTIVE' && selected.unallocatedMinor >= amountMinor,
  );
  const interactionsLocked = progress !== 'idle' || outcomeUnknown;
  const recoveryActionsLocked = interactionsLocked || completed;
  const displayOccurrence =
    occurrence &&
    (!lastOccurrence ||
      occurrence.definitionVersion > lastOccurrence.definitionVersion ||
      (occurrence.definitionVersion === lastOccurrence.definitionVersion &&
        occurrence.imports.length >= lastOccurrence.imports.length))
      ? occurrence
      : lastOccurrence;
  const liveOccurrenceUnavailable =
    !outcomeUnknown && (occurrenceUnavailable || (occurrenceResolved && !occurrence));
  const liveAssignedOccurrence = !outcomeUnknown
    ? (recoveryOccurrence ??
      (occurrenceResolved && occurrence && occurrence.imports.length > 0 ? occurrence : null))
    : null;
  const eligibleOccurrence =
    occurrenceResolved && !occurrence ? null : liveAssignedOccurrence ? null : displayOccurrence;
  const liveOccurrenceMessage = liveOccurrenceUnavailable
    ? 'This recurring Bill occurrence changed or is no longer available.'
    : liveAssignedOccurrence
      ? liveAssignedOccurrence.imports.length === 1
        ? 'This recurring Bill occurrence was already added to a paycheck.'
        : 'This recurring Bill occurrence already has imports to review.'
      : '';

  function openExistingImport() {
    if (recoveryActionsLocked || liveAssignedOccurrence?.imports.length !== 1) return;
    const imported = liveAssignedOccurrence.imports[0];
    onOpenImport(imported.paycheckId, imported.entryId);
  }

  function reviewExistingImports() {
    if (recoveryActionsLocked || !liveAssignedOccurrence) return;
    onReviewImports(liveAssignedOccurrence);
  }

  function viewTimeline() {
    if (recoveryActionsLocked || !liveOccurrenceUnavailable) return;
    onViewTimeline();
  }

  function beginAmountEdit() {
    if (interactionsLocked) return;
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

  function findImportedEntry(paycheck: Paycheck, attempt: ImportAttempt) {
    return paycheck.entries.find(
      (entry) =>
        !attempt.priorEntryIds.has(entry.id) &&
        entry.sourceRecurringBillDefinitionId === attempt.definitionId &&
        entry.sourceRecurringOccurrenceDate === attempt.occurrenceDate &&
        entry.amountMinor === attempt.amountMinor,
    );
  }

  async function finishSuccess(paycheckId: string, entryId: string) {
    if (completed) return;
    setCompleted(true);
    unresolvedAttempt.current = null;
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['dashboard', 'upcoming-recurring-bills'] }),
      queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'timeline'] }),
      queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'import-options'] }),
      queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'definitions'] }),
      queryClient.invalidateQueries({ queryKey: ['paychecks', 'active'] }),
      queryClient.invalidateQueries({ queryKey: ['paycheck', paycheckId] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard', 'summary'] }),
    ]);
    onCreated(paycheckId, entryId);
  }

  async function recoverNoMatch(refreshed: Paycheck) {
    setAuthoritativePaycheck(refreshed);
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'timeline'] }),
      queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'import-options'] }),
      queryClient.invalidateQueries({ queryKey: ['recurring-bills', 'definitions'] }),
    ]);
    const refreshedOccurrence = await refreshOccurrence();
    unresolvedAttempt.current = null;
    setOutcomeUnknown(false);
    if (!refreshedOccurrence) {
      setOccurrenceUnavailable(true);
      setRecoveryOccurrence(null);
      setError('This recurring Bill occurrence changed or is no longer available.');
      return;
    }
    setLastOccurrence(refreshedOccurrence);
    if (refreshedOccurrence.imports.length > 0) {
      setRecoveryOccurrence(refreshedOccurrence);
      setOccurrenceUnavailable(false);
      setError(
        refreshedOccurrence.imports.length === 1
          ? 'This recurring Bill occurrence was already added to a paycheck.'
          : 'This recurring Bill occurrence already has imports to review.',
      );
      return;
    }
    setRecoveryOccurrence(null);
    setOccurrenceUnavailable(false);
    setError(
      displayError(
        originalImportError.current,
        settings.currencyCode,
        'The recurring Bill was not added.',
      ),
    );
  }

  async function reconcile(attempt: ImportAttempt) {
    const refreshed = await api.paycheck(attempt.paycheckId);
    setAuthoritativePaycheck(refreshed);
    const created = findImportedEntry(refreshed, attempt);
    if (created) {
      setOutcomeUnknown(false);
      await finishSuccess(refreshed.id, created.id);
      return true;
    }
    await recoverNoMatch(refreshed);
    return false;
  }

  async function confirm() {
    if (
      !eligibleOccurrence ||
      !selected ||
      !canFit ||
      submission.current ||
      outcomeUnknown ||
      completed ||
      liveAssignedOccurrence ||
      liveOccurrenceUnavailable
    )
      return;
    submission.current = true;
    setProgress('importing');
    setError('');
    const attempt: ImportAttempt = {
      amountMinor,
      definitionId: eligibleOccurrence.definitionId,
      occurrenceDate: eligibleOccurrence.occurrenceDate,
      paycheckId: selected.id,
      priorEntryIds: new Set(selected.entries.map((entry) => entry.id)),
    };
    unresolvedAttempt.current = attempt;
    try {
      let result: Paycheck | null = null;
      try {
        result = await api.importRecurringBills(selected.id, selected.version, [
          {
            amountMinor,
            definitionId: eligibleOccurrence.definitionId,
            definitionVersion: eligibleOccurrence.definitionVersion,
            occurrenceDate: eligibleOccurrence.occurrenceDate,
            updateTypicalAmount,
          },
        ]);
      } catch (importError) {
        originalImportError.current = importError;
      }
      const created = result ? findImportedEntry(result, attempt) : undefined;
      if (created) {
        await finishSuccess(selected.id, created.id);
        return;
      }
      if (result)
        originalImportError.current = new Error(
          'The imported Bill could not be identified. Refresh and try again.',
        );
      try {
        await reconcile(attempt);
      } catch {
        setOutcomeUnknown(true);
        setError(
          'Yuuka could not confirm whether this Bill was added. Check the result before trying again to avoid adding it twice.',
        );
      }
    } finally {
      submission.current = false;
      setProgress('idle');
    }
  }

  async function checkResult() {
    const attempt = unresolvedAttempt.current;
    if (!attempt || submission.current) return;
    submission.current = true;
    setProgress('checking');
    try {
      await reconcile(attempt);
    } catch {
      setOutcomeUnknown(true);
      setError(
        'Yuuka could not confirm whether this Bill was added. Check the result before trying again to avoid adding it twice.',
      );
    } finally {
      submission.current = false;
      setProgress('idle');
    }
  }

  return (
    <>
      <Modal
        animationType="slide"
        onRequestClose={() => {
          if (!interactionsLocked) onClose();
        }}
        testID="quick-assignment-modal"
        visible={Boolean(assignmentIdentity)}
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
              accessibilityState={{ disabled: interactionsLocked }}
              disabled={interactionsLocked}
              onPress={onClose}
              style={styles.close}
            >
              <X color={colors.text} size={23} />
            </Pressable>
          </View>
          <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
            {displayOccurrence ? (
              <OccurrenceReview
                item={displayOccurrence}
                amountMinor={amountMinor}
                disabled={interactionsLocked}
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
            {effectivePaychecks.length === 0 ? (
              <View style={styles.empty}>
                <AppText>No Active paychecks are available.</AppText>
                <Button
                  disabled={interactionsLocked}
                  label="Create Paycheck"
                  onPress={onCreatePaycheck}
                  variant="secondary"
                />
              </View>
            ) : null}
            {effectivePaychecks.map((paycheck) => (
              <PaycheckChoice
                key={paycheck.id}
                amountMinor={amountMinor}
                interactionsDisabled={interactionsLocked}
                onSelect={() => {
                  setAuthoritativePaycheck(null);
                  setSelectedId(paycheck.id);
                }}
                paycheck={paycheck}
                selected={selectedId === paycheck.id}
              />
            ))}
            {effectivePaychecks.length &&
            effectivePaychecks.some(
              (item) => item.state === 'ACTIVE' && item.unallocatedMinor >= amountMinor,
            ) ? (
              <Button
                disabled={interactionsLocked}
                label="Create paycheck instead"
                onPress={onCreatePaycheck}
                variant="ghost"
              />
            ) : null}
            {effectivePaychecks.length &&
            !effectivePaychecks.some(
              (item) => item.state === 'ACTIVE' && item.unallocatedMinor >= amountMinor,
            ) ? (
              <View style={styles.empty}>
                <AppText style={{ color: colors.danger }} variant="caption">
                  No Active paycheck has enough unallocated money. Create a paycheck or adjust the
                  amount.
                </AppText>
                <Button
                  disabled={interactionsLocked}
                  label="Create Paycheck"
                  onPress={onCreatePaycheck}
                  variant="secondary"
                />
              </View>
            ) : null}
            {liveOccurrenceMessage ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {liveOccurrenceMessage}
              </AppText>
            ) : error ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {error}
              </AppText>
            ) : null}
            {outcomeUnknown ? (
              <Button
                label="Check result"
                onPress={() => void checkResult()}
                loading={progress === 'checking'}
                variant="secondary"
              />
            ) : null}
            {liveAssignedOccurrence?.imports.length === 1 ? (
              <Button
                disabled={recoveryActionsLocked}
                label="Open existing import"
                onPress={openExistingImport}
                variant="secondary"
              />
            ) : null}
            {liveAssignedOccurrence && liveAssignedOccurrence.imports.length > 1 ? (
              <Button
                disabled={recoveryActionsLocked}
                label="Review existing imports"
                onPress={reviewExistingImports}
                variant="secondary"
              />
            ) : null}
            {liveOccurrenceUnavailable ? (
              <Button
                disabled={recoveryActionsLocked}
                label="View Timeline"
                onPress={viewTimeline}
                variant="secondary"
              />
            ) : null}
            <Button
              disabled={
                !selected ||
                !canFit ||
                outcomeUnknown ||
                completed ||
                !eligibleOccurrence ||
                Boolean(liveAssignedOccurrence) ||
                liveOccurrenceUnavailable
              }
              icon={Check}
              label="Confirm import"
              loading={progress === 'importing'}
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
            <AppText variant="title">{displayOccurrence?.name}</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              Typical amount:{' '}
              {formatMoney(displayOccurrence?.typicalAmountMinor ?? 0, settings.currencyCode)}
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
  const inactive = paycheck.state !== 'ACTIVE';
  const disabled = shortfall > 0 || inactive || interactionsDisabled;
  const insufficient = shortfall > 0;
  const resulting = paycheck.unallocatedMinor - amountMinor;
  const availability = inactive
    ? `${paycheck.state === 'CLOSED' ? 'Closed' : 'Archived'}, unavailable`
    : insufficient
      ? `short by ${formatMoney(shortfall, settings.currencyCode)}, unavailable`
      : `${formatMoney(resulting, settings.currencyCode)} unallocated after import`;
  return (
    <Pressable
      accessibilityLabel={`${paycheck.name}, income date ${formatDate(paycheck.incomeDate)}, ${formatMoney(paycheck.unallocatedMinor, settings.currencyCode)} currently unallocated, ${availability}`}
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
        {inactive
          ? 'Unavailable'
          : insufficient
            ? 'Cannot fit'
            : formatMoney(resulting, settings.currencyCode)}
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
