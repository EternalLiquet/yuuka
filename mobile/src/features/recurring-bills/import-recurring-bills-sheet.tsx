import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Pencil, Plus, X } from 'lucide-react-native';
import { useMemo, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { RecurringBill, RecurringBillOccurrence } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export type RecurringBillImportSelection = RecurringBillOccurrence & {
  amountMinor: number;
  updateTypicalAmount: boolean;
};

export function ImportRecurringBillsSheet({
  incomeDate,
  localDraft = false,
  onClose,
  onImport,
  visible,
}: {
  incomeDate: string;
  localDraft?: boolean;
  onClose: () => void;
  onImport: (items: RecurringBillImportSelection[]) => Promise<void>;
  visible: boolean;
}) {
  const api = useYuukaApi();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [selected, setSelected] = useState<Record<string, RecurringBillImportSelection>>({});
  const [editing, setEditing] = useState<RecurringBillOccurrence | null>(null);
  const [amount, setAmount] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const definitions = useQuery({
    queryKey: ['recurring-bills', 'definitions', 'active'],
    queryFn: () => api.recurringBills('ACTIVE'),
    enabled: visible,
  });
  const owner = useQuery({ queryKey: ['me'], queryFn: () => api.me(), enabled: visible });
  const suggestionDays =
    owner.data?.recurringBillSuggestionDays ?? settings.recurringBillSuggestionDays ?? 7;
  const effectiveIncomeDate = validDate(incomeDate) ? incomeDate : todayDate();
  const rangeFrom = addDays(effectiveIncomeDate, -31);
  const rangeThrough = addDays(effectiveIncomeDate, 62);
  const timeline = useQuery({
    queryKey: ['recurring-bills', 'import-options', incomeDate],
    queryFn: () => api.recurringBillTimeline(rangeFrom, rangeThrough),
    enabled: visible,
  });
  const suggestedThrough = addDays(effectiveIncomeDate, suggestionDays);
  const suggested = useMemo(
    () =>
      (timeline.data?.items ?? []).filter(
        (item) =>
          item.occurrenceDate >= effectiveIncomeDate && item.occurrenceDate <= suggestedThrough,
      ),
    [effectiveIncomeDate, suggestedThrough, timeline.data?.items],
  );
  const all = useMemo(
    () =>
      nearestOccurrences(
        definitions.data?.items ?? [],
        timeline.data?.items ?? [],
        effectiveIncomeDate,
      ),
    [definitions.data?.items, effectiveIncomeDate, timeline.data?.items],
  );

  function resetAndClose() {
    setSelected({});
    setEditing(null);
    setError('');
    onClose();
  }

  function toggle(item: RecurringBillOccurrence) {
    const key = selectionKey(item);
    setSelected((current) => {
      if (current[key]) {
        const next = { ...current };
        delete next[key];
        return next;
      }
      return {
        ...current,
        [key]: recurringImportSelection(item, item.typicalAmountMinor, false),
      };
    });
  }

  function editAmount(item: RecurringBillOccurrence) {
    setEditing(item);
    setAmount(minorToInput(selected[selectionKey(item)]?.amountMinor ?? item.typicalAmountMinor));
  }

  function applyAmount(updateTypicalAmount: boolean) {
    if (!editing) return;
    try {
      const amountMinor = parseMoneyToMinor(amount);
      setSelected((current) =>
        updateRecurringAmountSelection(
          current,
          editing,
          amountMinor,
          updateTypicalAmount,
          localDraft,
        ),
      );
      setEditing(null);
      setError('');
    } catch (amountError) {
      setError(amountError instanceof Error ? amountError.message : 'Enter a valid amount.');
    }
  }

  async function importSelected() {
    const items = Object.values(selected);
    if (!items.length || saving) return;
    setSaving(true);
    setError('');
    try {
      if (localDraft) {
        const typicalUpdates = items.filter((value) => value.updateTypicalAmount);
        if (typicalUpdates.length > 1) {
          throw new Error('Update only one recurring Bill typical amount at a time.');
        }
        for (const item of typicalUpdates) {
          const definition = definitions.data?.items.find(
            (value) => value.id === item.definitionId,
          );
          if (!definition)
            throw new Error('Refresh recurring Bills before updating a typical amount.');
          await api.updateRecurringBill(definition.id, {
            name: definition.name,
            typicalAmountMinor: item.amountMinor,
            paymentMethod: definition.paymentMethod,
            dueDay: definition.dueDay,
            accountName: definition.accountName,
            payee: definition.payee,
            notes: definition.notes,
            version: definition.version,
          });
        }
      }
      await onImport(items);
      if (localDraft) await queryClient.invalidateQueries({ queryKey: ['recurring-bills'] });
      resetAndClose();
    } catch (importError) {
      setError(
        displayError(importError, settings.currencyCode, 'Recurring Bills were not imported.'),
      );
    } finally {
      setSaving(false);
    }
  }

  const definitionsMissing = !definitions.data;
  const timelineMissing = !timeline.data;
  const loading =
    (definitionsMissing && definitions.isPending) || (timelineMissing && timeline.isPending);
  const blockingError =
    (definitionsMissing ? definitions.error : null) ?? (timelineMissing ? timeline.error : null);
  const staleError =
    (!definitionsMissing && definitions.error) || (!timelineMissing && timeline.error);
  const ready = !definitionsMissing && !timelineMissing;
  const retryFailedRequiredQueries = () => {
    const retries: Promise<unknown>[] = [];
    if (definitions.error) retries.push(definitions.refetch());
    if (timeline.error) retries.push(timeline.refetch());
    if (!retries.length) {
      retries.push(definitions.refetch(), timeline.refetch());
    }
    return Promise.all(retries);
  };
  return (
    <>
      <Modal animationType="slide" onRequestClose={resetAndClose} visible={visible}>
        <View style={[styles.screen, { backgroundColor: colors.background }]}>
          <View style={[styles.header, { borderBottomColor: colors.border }]}>
            <View style={styles.headerText}>
              <AppText variant="title">Import recurring Bills</AppText>
              <AppText style={{ color: colors.muted }} variant="caption">
                Suggested through {formatDate(suggestedThrough)} · Nothing is selected automatically
              </AppText>
            </View>
            <Pressable
              accessibilityLabel="Close recurring Bill import"
              onPress={resetAndClose}
              style={styles.close}
            >
              <X color={colors.text} size={23} />
            </Pressable>
          </View>
          {loading ? <YuukaLoadingState message="Loading recurring Bills..." /> : null}
          {blockingError || (!loading && !ready) ? (
            <ErrorState
              message={displayError(
                blockingError,
                settings.currencyCode,
                'Recurring Bills could not load.',
              )}
              retry={() => void retryFailedRequiredQueries()}
            />
          ) : null}
          {ready ? (
            <ScrollView
              contentContainerStyle={styles.content}
              keyboardDismissMode="on-drag"
              keyboardShouldPersistTaps="handled"
            >
              {staleError ? (
                <View style={[styles.staleWarning, { borderColor: colors.border }]}>
                  <AppText variant="label">Showing saved recurring Bill data</AppText>
                  <AppText style={{ color: colors.muted }} variant="caption">
                    Some recurring Bill data could not refresh.
                  </AppText>
                  <Button
                    label="Retry recurring Bill data"
                    onPress={() => void retryFailedRequiredQueries()}
                    variant="secondary"
                  />
                </View>
              ) : null}
              <OccurrenceSection
                items={suggested}
                onEdit={editAmount}
                onToggle={toggle}
                selected={selected}
                title={`Suggested · next ${suggestionDays} days`}
              />
              <OccurrenceSection
                empty="No active recurring Bills."
                items={all}
                onEdit={editAmount}
                onToggle={toggle}
                selected={selected}
                title="All recurring Bills"
              />
              {error ? (
                <AppText style={{ color: colors.danger }} variant="error">
                  {error}
                </AppText>
              ) : null}
              <Button
                disabled={!Object.keys(selected).length}
                icon={Plus}
                label={`Add selected Bills (${Object.keys(selected).length})`}
                loading={saving}
                onPress={importSelected}
              />
            </ScrollView>
          ) : null}
        </View>
      </Modal>
      <Modal
        animationType="fade"
        onRequestClose={() => setEditing(null)}
        transparent
        visible={Boolean(editing)}
      >
        <View style={styles.amountBackdrop}>
          <View style={[styles.amountDialog, { backgroundColor: colors.surface }]}>
            <AppText variant="title">{editing?.name}</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              Typical amount: {formatMoney(editing?.typicalAmountMinor ?? 0, settings.currencyCode)}
            </AppText>
            <TextField
              keyboardType="decimal-pad"
              label="Amount for this paycheck"
              onChangeText={setAmount}
              value={amount}
            />
            <AppText variant="label">Update the recurring Bill&apos;s typical amount?</AppText>
            <Button label="This paycheck only" onPress={() => applyAmount(false)} />
            <Button
              label="Update typical amount"
              onPress={() => applyAmount(true)}
              variant="secondary"
            />
            <Button label="Cancel" onPress={() => setEditing(null)} variant="ghost" />
          </View>
        </View>
      </Modal>
    </>
  );
}

function OccurrenceSection({
  empty = 'No recurring Bills are due in this window.',
  items,
  onEdit,
  onToggle,
  selected,
  title,
}: {
  empty?: string;
  items: RecurringBillOccurrence[];
  onEdit: (item: RecurringBillOccurrence) => void;
  onToggle: (item: RecurringBillOccurrence) => void;
  selected: Record<string, RecurringBillImportSelection>;
  title: string;
}) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.section}>
      <AppText variant="label">{title}</AppText>
      {!items.length ? <AppText style={{ color: colors.muted }}>{empty}</AppText> : null}
      {items.map((item) => {
        const selectedItem = selected[selectionKey(item)];
        return (
          <View
            key={selectionKey(item)}
            style={[
              styles.option,
              {
                backgroundColor: colors.surface,
                borderColor: selectedItem ? colors.accent : colors.border,
              },
            ]}
          >
            <Pressable
              accessibilityLabel={`Select ${item.name}, due ${formatDate(item.occurrenceDate)}`}
              accessibilityRole="checkbox"
              accessibilityState={{ checked: Boolean(selectedItem) }}
              onLongPress={() => onEdit(item)}
              onPress={() => onToggle(item)}
              style={styles.optionMain}
            >
              <View
                style={[
                  styles.checkbox,
                  {
                    backgroundColor: selectedItem ? colors.accent : 'transparent',
                    borderColor: selectedItem ? colors.accent : colors.border,
                  },
                ]}
              >
                {selectedItem ? <Check color={colors.background} size={15} /> : null}
              </View>
              <View style={styles.optionText}>
                <AppText variant="label">{item.name}</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  Due {formatDate(item.occurrenceDate)} ·{' '}
                  {item.paymentMethod === 'MANUAL' ? 'Manual' : 'Autopay'}
                </AppText>
                <AppText variant="money">
                  {formatMoney(selectedItem?.amountMinor ?? item.typicalAmountMinor)}
                </AppText>
              </View>
            </Pressable>
            <Pressable
              accessibilityLabel={`Edit amount for ${item.name}`}
              accessibilityRole="button"
              onPress={() => onEdit(item)}
              style={styles.edit}
            >
              <Pencil color={colors.accent} size={20} />
            </Pressable>
          </View>
        );
      })}
    </View>
  );
}

function nearestOccurrences(
  definitions: RecurringBill[],
  occurrences: RecurringBillOccurrence[],
  incomeDate: string,
) {
  return definitions
    .map((definition) => {
      const matching = occurrences.filter((item) => item.definitionId === definition.id);
      return matching.sort((left, right) =>
        compareDistance(left.occurrenceDate, right.occurrenceDate, incomeDate),
      )[0];
    })
    .filter((item): item is RecurringBillOccurrence => Boolean(item))
    .sort(
      (left, right) =>
        left.occurrenceDate.localeCompare(right.occurrenceDate) ||
        left.name.localeCompare(right.name),
    );
}

function compareDistance(left: string, right: string, incomeDate: string) {
  const leftDistance = Math.abs(daysBetween(incomeDate, left));
  const rightDistance = Math.abs(daysBetween(incomeDate, right));
  if (leftDistance !== rightDistance) return leftDistance - rightDistance;
  return right.localeCompare(left);
}

function daysBetween(start: string, end: string) {
  return Math.round(
    (new Date(`${end}T00:00:00Z`).getTime() - new Date(`${start}T00:00:00Z`).getTime()) /
      86_400_000,
  );
}

function selectionKey(item: RecurringBillOccurrence) {
  return `${item.definitionId}:${item.occurrenceDate}`;
}

export function recurringImportSelection(
  occurrence: RecurringBillOccurrence,
  amountMinor = occurrence.typicalAmountMinor,
  updateTypicalAmount = false,
): RecurringBillImportSelection {
  return { ...occurrence, amountMinor, updateTypicalAmount };
}

export function updateRecurringAmountSelection(
  current: Record<string, RecurringBillImportSelection>,
  occurrence: RecurringBillOccurrence,
  amountMinor: number,
  updateTypicalAmount: boolean,
  singleTypicalUpdateAcrossImport: boolean,
): Record<string, RecurringBillImportSelection> {
  const next = Object.fromEntries(
    Object.entries(current).map(([key, item]) => [
      key,
      updateTypicalAmount &&
      item.updateTypicalAmount &&
      (singleTypicalUpdateAcrossImport || item.definitionId === occurrence.definitionId)
        ? { ...item, updateTypicalAmount: false }
        : item,
    ]),
  );
  next[selectionKey(occurrence)] = recurringImportSelection(
    occurrence,
    amountMinor,
    updateTypicalAmount,
  );
  return next;
}

function addDays(date: string, days: number) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function validDate(value: string) {
  return (
    /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(new Date(`${value}T00:00:00Z`).getTime())
  );
}

function todayDate() {
  const value = new Date();
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
}

function formatDate(date: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${date}T00:00:00`));
}

const styles = StyleSheet.create({
  amountBackdrop: {
    backgroundColor: 'rgba(0,0,0,0.6)',
    flex: 1,
    justifyContent: 'center',
    padding: 24,
  },
  amountDialog: { borderRadius: 10, gap: 14, padding: 18 },
  checkbox: {
    alignItems: 'center',
    borderRadius: 4,
    borderWidth: 1,
    height: 22,
    justifyContent: 'center',
    width: 22,
  },
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  content: { gap: 22, padding: 18, paddingBottom: 42 },
  edit: { alignItems: 'center', height: 48, justifyContent: 'center', width: 48 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    gap: 12,
    padding: 16,
  },
  headerText: { flex: 1, gap: 4 },
  option: { alignItems: 'center', borderRadius: 8, borderWidth: 1, flexDirection: 'row' },
  optionMain: {
    alignItems: 'center',
    flex: 1,
    flexDirection: 'row',
    gap: 11,
    minHeight: 76,
    padding: 12,
  },
  optionText: { flex: 1, gap: 4 },
  screen: { flex: 1 },
  section: { gap: 10 },
  staleWarning: { borderRadius: 8, borderWidth: 1, gap: 8, padding: 12 },
});
