import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ArrowLeft, CheckCircle2, Pencil, RotateCcw, Save, Trash2 } from 'lucide-react-native';
import { useMemo, useState } from 'react';
import { Alert, Pressable, StyleSheet, View } from 'react-native';

import { ExpenseLedgerItem } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { IconButton } from '@/components/icon-button';
import { ScrollScreen } from '@/components/scroll-screen';
import { TextField } from '@/components/text-field';
import { ErrorState } from '@/components/states';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { expenseLedgerSettlementTargetPath } from '@/features/expense-ledgers/navigation';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

type ItemDraft = {
  amount: string;
  expenseDate: string;
  merchant: string;
  name: string;
  notes: string;
};

const emptyDraft = (): ItemDraft => ({
  amount: '',
  expenseDate: todayIso(),
  merchant: '',
  name: '',
  notes: '',
});

export default function ExpenseLedgerDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [draft, setDraft] = useState<ItemDraft>(emptyDraft);
  const [editingItemId, setEditingItemId] = useState<string | null>(null);
  const [settlementMode, setSettlementMode] = useState<'bill' | 'payback' | null>(null);
  const ledgerQuery = useQuery({
    queryKey: ['expense-ledger', id],
    queryFn: () => api.expenseLedger(id),
    enabled: Boolean(id),
  });
  const activePaychecksQuery = useQuery({
    queryKey: ['paychecks', 'active', 'expense-ledger-settlement'],
    queryFn: api.activePaychecks,
    enabled: settlementMode === 'bill',
  });
  const ledger = ledgerQuery.data;
  const editingItem = useMemo(
    () => ledger?.items.find((item) => item.id === editingItemId) ?? null,
    [editingItemId, ledger?.items],
  );
  const settlementTargetPath = ledger?.settlement
    ? expenseLedgerSettlementTargetPath(ledger.settlement)
    : null;

  const refreshLedgers = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['expense-ledgers'] }),
      queryClient.invalidateQueries({ queryKey: ['expense-ledger', id] }),
    ]);
  };

  const saveItemMutation = useMutation({
    mutationFn: async ({ close }: { close: boolean }) => {
      const amountMinor = parseMoneyToMinor(draft.amount);
      const payload = {
        amountMinor,
        expenseDate: draft.expenseDate,
        merchant: draft.merchant.trim() || null,
        name: draft.name.trim() || null,
        notes: draft.notes.trim() || null,
      };
      if (editingItem) {
        return api.updateExpenseLedgerItem(editingItem.id, {
          ...payload,
          version: editingItem.version,
        });
      }
      return api.addExpenseLedgerItem(id, payload);
    },
    onSuccess: async (_item, variables) => {
      await refreshLedgers();
      setEditingItemId(null);
      setDraft(
        variables.close ? emptyDraft() : { ...emptyDraft(), expenseDate: draft.expenseDate },
      );
    },
  });

  const deleteItemMutation = useMutation({
    mutationFn: (item: ExpenseLedgerItem) => api.deleteExpenseLedgerItem(item.id, item.version),
    onSuccess: refreshLedgers,
  });
  const finalizeMutation = useMutation({
    mutationFn: () => {
      if (!ledger) throw new Error('Refresh the Expense List before finalizing.');
      return api.finalizeExpenseLedger(id, ledger.version);
    },
    onSuccess: refreshLedgers,
  });
  const reopenMutation = useMutation({
    mutationFn: () => {
      if (!ledger) throw new Error('Refresh the Expense List before reopening.');
      return api.reopenExpenseLedger(id, ledger.version);
    },
    onSuccess: refreshLedgers,
  });
  const deleteLedgerMutation = useMutation({
    mutationFn: () => {
      if (!ledger) throw new Error('Refresh the Expense List before deleting.');
      return api.deleteExpenseLedger(id, ledger.version);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['expense-ledgers'] });
      router.replace('/expense-ledgers');
    },
  });
  const settleBillMutation = useMutation({
    mutationFn: (paycheckId: string) => {
      if (!ledger) throw new Error('Refresh the Expense List before settling.');
      return api.settleExpenseLedgerAsBill(id, { ledgerVersion: ledger.version, paycheckId });
    },
    onSuccess: async () => {
      setSettlementMode(null);
      await Promise.all([
        refreshLedgers(),
        queryClient.invalidateQueries({ queryKey: ['paycheck'] }),
        queryClient.invalidateQueries({ queryKey: ['paychecks'] }),
      ]);
    },
  });
  const settlePaybackMutation = useMutation({
    mutationFn: () => {
      if (!ledger) throw new Error('Refresh the Expense List before settling.');
      return api.settleExpenseLedgerAsPayback(id, { ledgerVersion: ledger.version });
    },
    onSuccess: async () => {
      setSettlementMode(null);
      await Promise.all([
        refreshLedgers(),
        queryClient.invalidateQueries({ queryKey: ['paybacks'] }),
      ]);
    },
  });

  function editItem(item: ExpenseLedgerItem) {
    setEditingItemId(item.id);
    setDraft({
      amount: minorToInput(item.amountMinor),
      expenseDate: item.expenseDate,
      merchant: item.merchant ?? '',
      name: item.name ?? '',
      notes: item.notes ?? '',
    });
  }

  if (ledgerQuery.isError || (!ledgerQuery.isPending && !ledger)) {
    return (
      <ScrollScreen contentContainerStyle={styles.content}>
        <IconButton icon={ArrowLeft} label="Back" onPress={() => router.back()} />
        <ErrorState
          message={displayError(
            ledgerQuery.error,
            settings.currencyCode,
            'Expense List could not be loaded.',
          )}
          retry={() => ledgerQuery.refetch()}
        />
      </ScrollScreen>
    );
  }

  return (
    <ScrollScreen contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <IconButton icon={ArrowLeft} label="Back" onPress={() => router.back()} />
        {ledger && ledger.state !== 'SETTLED' ? (
          <Button
            icon={Trash2}
            label="Delete"
            loading={deleteLedgerMutation.isPending}
            onPress={() =>
              Alert.alert('Delete Expense List?', ledger.name, [
                { text: 'Cancel', style: 'cancel' },
                {
                  text: 'Delete',
                  style: 'destructive',
                  onPress: () => deleteLedgerMutation.mutate(),
                },
              ])
            }
            variant="danger"
          />
        ) : null}
      </View>
      {ledger ? (
        <>
          <View
            style={[
              styles.summary,
              { backgroundColor: colors.surface, borderColor: colors.border },
            ]}
          >
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">{ledger.name}</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {ledger.state} | {ledger.itemCount} items
                </AppText>
              </View>
              <AppText variant="money">
                {formatMoney(ledger.totalMinor, settings.currencyCode)}
              </AppText>
            </View>
            {ledger.settlement && settlementTargetPath ? (
              <Pressable
                accessibilityRole="button"
                onPress={() => router.push(settlementTargetPath)}
              >
                <AppText style={{ color: colors.accent }} variant="label">
                  {ledger.settlement.settlementType === 'BILL' ? 'Bill' : 'Payback'} target
                </AppText>
              </Pressable>
            ) : null}
          </View>

          {ledger.state === 'OPEN' ? (
            <ItemEditor
              draft={draft}
              editingItem={editingItem}
              error={saveItemMutation.error}
              loading={saveItemMutation.isPending}
              onCancelEdit={() => {
                setEditingItemId(null);
                setDraft(emptyDraft());
              }}
              onChange={setDraft}
              onSave={(close) => saveItemMutation.mutate({ close })}
            />
          ) : null}

          <View style={styles.actions}>
            {ledger.state === 'OPEN' ? (
              <Button
                disabled={ledger.itemCount === 0}
                icon={CheckCircle2}
                label="Finalize"
                loading={finalizeMutation.isPending}
                onPress={() => finalizeMutation.mutate()}
              />
            ) : null}
            {ledger.state === 'FINALIZED' ? (
              <>
                <Button
                  icon={RotateCcw}
                  label="Reopen"
                  loading={reopenMutation.isPending}
                  onPress={() => reopenMutation.mutate()}
                  variant="secondary"
                />
                <Button label="Settle as Bill" onPress={() => setSettlementMode('bill')} />
                <Button
                  label="Settle as Payback"
                  onPress={() => setSettlementMode('payback')}
                  variant="secondary"
                />
              </>
            ) : null}
          </View>

          {settlementMode === 'bill' ? (
            <View
              style={[
                styles.panel,
                { backgroundColor: colors.surface, borderColor: colors.border },
              ]}
            >
              <AppText variant="label">Settle as Bill</AppText>
              {activePaychecksQuery.data?.items.map((paycheck) => (
                <Button
                  key={paycheck.id}
                  label={`${paycheck.name}  ${formatMoney(paycheck.unallocatedMinor, settings.currencyCode)}`}
                  loading={settleBillMutation.isPending}
                  onPress={() => settleBillMutation.mutate(paycheck.id)}
                  variant="secondary"
                />
              ))}
              {activePaychecksQuery.isError ? (
                <AppText style={{ color: colors.danger }} variant="error">
                  {displayError(
                    activePaychecksQuery.error,
                    settings.currencyCode,
                    'Paychecks could not be loaded.',
                  )}
                </AppText>
              ) : null}
            </View>
          ) : null}

          {settlementMode === 'payback' ? (
            <View
              style={[
                styles.panel,
                { backgroundColor: colors.surface, borderColor: colors.border },
              ]}
            >
              <AppText variant="label">Settle as Payback</AppText>
              <Button
                icon={Save}
                label="Create Payback"
                loading={settlePaybackMutation.isPending}
                onPress={() => settlePaybackMutation.mutate()}
              />
            </View>
          ) : null}

          <MutationError
            errors={[
              deleteLedgerMutation.error,
              deleteItemMutation.error,
              finalizeMutation.error,
              reopenMutation.error,
              settleBillMutation.error,
              settlePaybackMutation.error,
            ]}
          />

          <View style={styles.section}>
            <AppText variant="label">Expenses</AppText>
            {ledger.items.map((item) => (
              <View
                key={item.id}
                style={[
                  styles.itemRow,
                  { backgroundColor: colors.surface, borderColor: colors.border },
                ]}
              >
                <View style={styles.itemText}>
                  <AppText variant="label">{item.name || item.merchant}</AppText>
                  <AppText style={{ color: colors.muted }} variant="caption">
                    {item.expenseDate} | {item.merchant || item.name}
                  </AppText>
                </View>
                <AppText variant="label">
                  {formatMoney(item.amountMinor, settings.currencyCode)}
                </AppText>
                {ledger.state === 'OPEN' ? (
                  <View style={styles.itemActions}>
                    <IconButton
                      icon={Pencil}
                      label={`Edit ${item.name || item.merchant}`}
                      onPress={() => editItem(item)}
                    />
                    <IconButton
                      icon={Trash2}
                      label={`Delete ${item.name || item.merchant}`}
                      onPress={() => deleteItemMutation.mutate(item)}
                    />
                  </View>
                ) : null}
              </View>
            ))}
          </View>
        </>
      ) : null}
    </ScrollScreen>
  );
}

function ItemEditor({
  draft,
  editingItem,
  error,
  loading,
  onCancelEdit,
  onChange,
  onSave,
}: {
  draft: ItemDraft;
  editingItem: ExpenseLedgerItem | null;
  error: unknown;
  loading: boolean;
  onCancelEdit: () => void;
  onChange: (draft: ItemDraft) => void;
  onSave: (close: boolean) => void;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const canSave =
    draft.amount.trim().length > 0 &&
    (draft.name.trim().length > 0 || draft.merchant.trim().length > 0);
  return (
    <View style={[styles.panel, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <AppText variant="label">{editingItem ? 'Edit Expense' : 'Add Expense'}</AppText>
      <View style={styles.twoColumn}>
        <TextField
          containerStyle={styles.flex}
          keyboardType="decimal-pad"
          label="Amount"
          onChangeText={(amount) => onChange({ ...draft, amount })}
          value={draft.amount}
        />
        <TextField
          containerStyle={styles.flex}
          label="Date"
          onChangeText={(expenseDate) => onChange({ ...draft, expenseDate })}
          value={draft.expenseDate}
        />
      </View>
      <TextField
        label="Name"
        onChangeText={(name) => onChange({ ...draft, name })}
        value={draft.name}
      />
      <TextField
        label="Merchant"
        onChangeText={(merchant) => onChange({ ...draft, merchant })}
        value={draft.merchant}
      />
      <TextField
        label="Notes"
        multiline
        onChangeText={(notes) => onChange({ ...draft, notes })}
        textAlignVertical="top"
        value={draft.notes}
      />
      {error ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {displayError(error, settings.currencyCode, 'Expense was not saved.')}
        </AppText>
      ) : null}
      <View style={styles.actions}>
        <Button
          disabled={!canSave}
          label={editingItem ? 'Save and close' : 'Save and add another'}
          loading={loading}
          onPress={() => onSave(Boolean(editingItem))}
        />
        {!editingItem ? (
          <Button
            disabled={!canSave}
            label="Save and close"
            loading={loading}
            onPress={() => onSave(true)}
            variant="secondary"
          />
        ) : (
          <Button label="Cancel" onPress={onCancelEdit} variant="secondary" />
        )}
      </View>
    </View>
  );
}

function MutationError({ errors }: { errors: unknown[] }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const error = errors.find(Boolean);
  if (!error) return null;
  return (
    <AppText style={{ color: colors.danger }} variant="error">
      {displayError(error, settings.currencyCode, 'Expense List action failed.')}
    </AppText>
  );
}

function todayIso() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(
    now.getDate(),
  ).padStart(2, '0')}`;
}

const styles = StyleSheet.create({
  actions: { gap: 10 },
  content: { gap: 14, paddingBottom: 28 },
  flex: { flex: 1 },
  header: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  itemActions: { flexDirection: 'row', gap: 6 },
  itemRow: { borderRadius: 8, borderWidth: 1, gap: 8, padding: 12 },
  itemText: { flex: 1, gap: 2 },
  panel: { borderRadius: 8, borderWidth: 1, gap: 12, padding: 12 },
  section: { gap: 10 },
  summary: { borderRadius: 8, borderWidth: 1, gap: 10, padding: 14 },
  titleBlock: { flex: 1, gap: 3 },
  titleRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
  },
  twoColumn: { flexDirection: 'row', gap: 10 },
});
