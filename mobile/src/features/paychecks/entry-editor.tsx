import { zodResolver } from '@hookform/resolvers/zod';
import { Check, ChevronDown, RefreshCw, Save, Trash2, X } from 'lucide-react-native';
import { useEffect, useMemo, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { Entry, EntryPaymentMethod, Payback, SinkingFund } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { SegmentedControl } from '@/components/segmented-control';
import { TextField } from '@/components/text-field';
import { minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

import { EntryFormValues, entryFormSchema } from './form-schemas';

const typeOptions = [
  { label: 'Bill', value: 'BILL' },
  { label: 'Spending Bucket', value: 'SPENDING_BUCKET' },
  { label: 'Planned Savings', value: 'SINKING_FUND' },
] as const;

export function EntryEditor({
  entry,
  onClose,
  onDelete,
  onSubmit,
  paybacks = [],
  paybacksError,
  paybacksLoading,
  onRetryPaybacks,
  onRetrySinkingFunds,
  visible,
  sinkingFunds = [],
  sinkingFundsError,
  sinkingFundsLoading,
}: {
  entry?: Entry | null;
  onClose: () => void;
  onDelete?: () => Promise<void>;
  onSubmit: (values: {
    accountName: string | null;
    amountMinor: number;
    dueDate: string | null;
    entryType: EntryFormValues['entryType'];
    name: string;
    notes: string | null;
    paymentMethod: EntryPaymentMethod | null;
    payee: string | null;
    paybackId: string | null;
    sinkingFundId: string | null;
    targetDate: string | null;
    targetMinor: number | null;
  }) => Promise<void>;
  onRetryPaybacks?: () => void;
  onRetrySinkingFunds?: () => void;
  paybacks?: Payback[];
  paybacksError?: string | null;
  paybacksLoading?: boolean;
  sinkingFunds?: SinkingFund[];
  sinkingFundsError?: string | null;
  sinkingFundsLoading?: boolean;
  visible: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    reset,
    setError,
    setValue,
  } = useForm<EntryFormValues>({
    resolver: zodResolver(entryFormSchema),
    defaultValues: defaults(entry),
  });
  const entryType = useWatch({ control, name: 'entryType' });
  const sinkingFundId = useWatch({ control, name: 'sinkingFundId' });

  useEffect(() => {
    if (visible) reset(defaults(entry));
  }, [entry, reset, visible]);

  useEffect(() => {
    if (entryType !== 'BILL') {
      setValue('manualPay', false);
    }
    if (entryType !== 'SINKING_FUND') {
      setValue('sinkingFundId', '');
    }
  }, [entryType, setValue]);

  async function submit(values: EntryFormValues) {
    const persistentSinkingFundId =
      values.entryType === 'SINKING_FUND' ? values.sinkingFundId || null : null;
    try {
      await onSubmit({
        entryType: values.entryType,
        name: values.name.trim(),
        amountMinor: parseMoneyToMinor(values.amount),
        paymentMethod:
          values.entryType === 'BILL' ? (values.manualPay ? 'MANUAL' : 'AUTOPAY') : null,
        dueDate: values.entryType === 'BILL' && values.dueDate ? values.dueDate : null,
        accountName:
          values.entryType === 'BILL' && values.accountName.trim()
            ? values.accountName.trim()
            : null,
        payee: values.entryType === 'BILL' && values.payee.trim() ? values.payee.trim() : null,
        notes: values.notes.trim() || null,
        paybackId: persistentSinkingFundId ? null : values.paybackId || null,
        sinkingFundId: persistentSinkingFundId,
        targetMinor:
          values.entryType === 'SINKING_FUND' && !values.sinkingFundId && values.target
            ? parseMoneyToMinor(values.target)
            : null,
        targetDate:
          values.entryType === 'SINKING_FUND' && !values.sinkingFundId && values.targetDate
            ? values.targetDate
            : null,
      });
      onClose();
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The entry was not saved.'),
      });
    }
  }

  async function remove() {
    if (!onDelete) return;
    try {
      await onDelete();
      onClose();
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The entry was not deleted.'),
      });
    }
  }

  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={visible}>
      <View style={[styles.screen, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <View>
            <AppText variant="title">{entry ? 'Edit entry' : 'New entry'}</AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {entry?.name ?? 'Paycheck allocation'}
            </AppText>
          </View>
          <Pressable accessibilityLabel="Close entry editor" onPress={onClose} style={styles.close}>
            <X color={colors.text} size={23} />
          </Pressable>
        </View>
        <ScrollView
          contentContainerStyle={styles.form}
          keyboardDismissMode="on-drag"
          keyboardShouldPersistTaps="handled"
        >
          <Controller
            control={control}
            name="entryType"
            render={({ field }) => (
              <View style={styles.fieldGroup}>
                <AppText variant="label">Type</AppText>
                <SegmentedControl
                  label="Entry type"
                  onChange={field.onChange}
                  options={typeOptions}
                  value={field.value}
                />
              </View>
            )}
          />
          <ControlledField
            control={control}
            error={errors.name?.message}
            label="Name"
            name="name"
          />
          <ControlledField
            control={control}
            error={errors.amount?.message}
            keyboardType="decimal-pad"
            label={entryType === 'SPENDING_BUCKET' ? 'Budget amount' : 'Amount'}
            name="amount"
          />
          {entryType === 'BILL' ? (
            <>
              <ControlledField
                control={control}
                error={errors.dueDate?.message}
                label="Due date (optional)"
                name="dueDate"
                placeholder="YYYY-MM-DD"
              />
              <ControlledField control={control} label="Account (optional)" name="accountName" />
              <ControlledField control={control} label="Payee (optional)" name="payee" />
              <Controller
                control={control}
                name="manualPay"
                render={({ field }) => (
                  <Pressable
                    accessibilityLabel="I need to pay this manually"
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: Boolean(field.value) }}
                    onPress={() => field.onChange(!field.value)}
                    style={({ pressed }) => [
                      styles.checkboxRow,
                      { backgroundColor: colors.surfaceElevated, borderColor: colors.border },
                      pressed && styles.pressed,
                    ]}
                  >
                    <View
                      style={[
                        styles.checkbox,
                        {
                          backgroundColor: field.value ? colors.accent : 'transparent',
                          borderColor: field.value ? colors.accent : colors.border,
                        },
                      ]}
                    >
                      {field.value ? <Check color={colors.background} size={15} /> : null}
                    </View>
                    <AppText variant="label">I need to pay this manually</AppText>
                  </Pressable>
                )}
              />
            </>
          ) : null}
          {entryType === 'SINKING_FUND' ? (
            <>
              <Controller
                control={control}
                name="sinkingFundId"
                render={({ field }) => (
                  <View style={styles.fieldGroup}>
                    <SinkingFundSelector
                      currentSinkingFundId={entry?.sinkingFundId ?? null}
                      error={sinkingFundsError}
                      loading={sinkingFundsLoading}
                      onChange={(value) => {
                        field.onChange(value);
                        if (value) setValue('paybackId', '');
                      }}
                      onRetry={onRetrySinkingFunds}
                      sinkingFunds={sinkingFunds}
                      value={field.value}
                    />
                  </View>
                )}
              />
              {!sinkingFundId ? (
                <>
                  <ControlledField
                    control={control}
                    error={errors.target?.message}
                    keyboardType="decimal-pad"
                    label="Target amount (optional)"
                    name="target"
                  />
                  <ControlledField
                    control={control}
                    error={errors.targetDate?.message}
                    label="Target date (optional)"
                    name="targetDate"
                    placeholder="YYYY-MM-DD"
                  />
                </>
              ) : null}
            </>
          ) : null}
          <ControlledField control={control} label="Notes (optional)" multiline name="notes" />
          <Controller
            control={control}
            name="paybackId"
            render={({ field }) => (
              <View style={styles.fieldGroup}>
                <PaybackSelector
                  currentPaybackId={entry?.paybackId ?? null}
                  error={paybacksError}
                  loading={paybacksLoading}
                  onChange={(value) => {
                    field.onChange(value);
                    if (value) setValue('sinkingFundId', '');
                  }}
                  onRetry={onRetryPaybacks}
                  paybacks={paybacks}
                  value={field.value}
                />
              </View>
            )}
          />
          {errors.root?.message ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {errors.root.message}
            </AppText>
          ) : null}
          <Button
            icon={Save}
            label="Save entry"
            loading={isSubmitting}
            onPress={handleSubmit(submit)}
          />
          {entry && onDelete ? (
            <Button icon={Trash2} label="Delete entry" onPress={remove} variant="danger" />
          ) : null}
        </ScrollView>
      </View>
    </Modal>
  );
}

function ControlledField({
  control,
  error,
  keyboardType,
  label,
  multiline,
  name,
  placeholder,
}: {
  control: ReturnType<typeof useForm<EntryFormValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad';
  label: string;
  multiline?: boolean;
  name: keyof EntryFormValues;
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

function defaults(entry?: Entry | null): EntryFormValues {
  return {
    entryType: entry?.entryType ?? 'BILL',
    name: entry?.name ?? '',
    amount: entry ? minorToInput(entry.amountMinor) : '',
    dueDate: entry?.dueDate ?? '',
    manualPay: entry?.paymentMethod === 'MANUAL',
    accountName: entry?.accountName ?? '',
    payee: entry?.payee ?? '',
    notes: entry?.notes ?? '',
    paybackId: entry?.paybackId ?? '',
    sinkingFundId: entry?.sinkingFundId ?? '',
    target: entry?.targetMinor == null ? '' : minorToInput(entry.targetMinor),
    targetDate: entry?.targetDate ?? '',
  };
}

function SinkingFundSelector({
  currentSinkingFundId,
  error,
  loading,
  onChange,
  onRetry,
  sinkingFunds,
  value,
}: {
  currentSinkingFundId: string | null;
  error?: string | null;
  loading?: boolean;
  onChange: (value: string) => void;
  onRetry?: () => void;
  sinkingFunds: SinkingFund[];
  value: string;
}) {
  const { colors } = useAppTheme();
  const [open, setOpen] = useState(false);
  const options = useMemo(
    () =>
      sinkingFunds
        .filter((fund) => fund.state === 'ACTIVE' || fund.id === currentSinkingFundId)
        .sort(
          (left, right) => left.position - right.position || left.name.localeCompare(right.name),
        ),
    [currentSinkingFundId, sinkingFunds],
  );
  const selected = options.find((fund) => fund.id === value);
  const selectedLabel = selected?.name ?? 'No planned savings';
  return (
    <>
      <AppText variant="label">Planned Savings</AppText>
      <AppText style={{ color: colors.muted }} variant="caption">
        Posted entries add to the selected Planned Savings balance.
      </AppText>
      <Pressable
        accessible
        accessibilityLabel={`Planned Savings, selected ${selectedLabel}`}
        accessibilityRole="button"
        onPress={() => setOpen(true)}
        style={({ pressed }) => [
          styles.paybackSelect,
          { backgroundColor: colors.surfaceElevated, borderColor: colors.border },
          pressed && styles.pressed,
        ]}
      >
        <View style={styles.paybackSelectText}>
          <AppText numberOfLines={1} variant="label">
            {selectedLabel}
          </AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {loading ? 'Loading Planned Savings...' : 'Choose Planned Savings'}
          </AppText>
        </View>
        <ChevronDown color={colors.text} size={20} />
      </Pressable>
      <Modal animationType="slide" onRequestClose={() => setOpen(false)} transparent visible={open}>
        <View style={styles.sheetBackdrop}>
          <View style={[styles.sheet, { backgroundColor: colors.background }]}>
            <View style={[styles.sheetHeader, { borderBottomColor: colors.border }]}>
              <View>
                <AppText variant="title">Planned Savings</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  Choose where Posted contributions are tracked.
                </AppText>
              </View>
              <Pressable
                accessibilityLabel="Close Planned Savings selector"
                onPress={() => setOpen(false)}
                style={styles.close}
              >
                <X color={colors.text} size={23} />
              </Pressable>
            </View>
            <ScrollView contentContainerStyle={styles.optionList}>
              <PaybackSelectOption
                label="No planned savings"
                onPress={() => {
                  onChange('');
                  setOpen(false);
                }}
                selected={!value}
              />
              {loading ? (
                <View style={styles.selectorState}>
                  <ActivityIndicator color={colors.accent} />
                  <AppText style={{ color: colors.muted }} variant="caption">
                    Loading Planned Savings...
                  </AppText>
                </View>
              ) : error ? (
                <View style={styles.selectorState}>
                  <AppText style={{ color: colors.danger }} variant="error">
                    {error}
                  </AppText>
                  {onRetry ? (
                    <Button icon={RefreshCw} label="Retry" onPress={onRetry} variant="secondary" />
                  ) : null}
                </View>
              ) : options.length ? (
                options.map((fund) => (
                  <PaybackSelectOption
                    key={fund.id}
                    label={fund.name}
                    onPress={() => {
                      onChange(fund.id);
                      setOpen(false);
                    }}
                    selected={value === fund.id}
                  />
                ))
              ) : (
                <View style={styles.selectorState}>
                  <AppText variant="label">No active Planned Savings</AppText>
                  <AppText style={{ color: colors.muted, textAlign: 'center' }} variant="caption">
                    Create active Planned Savings before assigning contributions.
                  </AppText>
                </View>
              )}
            </ScrollView>
          </View>
        </View>
      </Modal>
    </>
  );
}

function PaybackSelector({
  currentPaybackId,
  error,
  loading,
  onChange,
  onRetry,
  paybacks,
  value,
}: {
  currentPaybackId: string | null;
  error?: string | null;
  loading?: boolean;
  onChange: (value: string) => void;
  onRetry?: () => void;
  paybacks: Payback[];
  value: string;
}) {
  const { colors } = useAppTheme();
  const [open, setOpen] = useState(false);
  const options = useMemo(
    () =>
      paybacks
        .filter((payback) => payback.state === 'ACTIVE' || payback.id === currentPaybackId)
        .sort(
          (left, right) => left.position - right.position || left.name.localeCompare(right.name),
        ),
    [currentPaybackId, paybacks],
  );
  const selected = options.find((payback) => payback.id === value);
  const selectedLabel = selected?.name ?? 'No Payback';
  return (
    <>
      <AppText variant="label">Apply to Payback</AppText>
      <AppText style={{ color: colors.muted }} variant="caption">
        Repayment applies when this entry reaches Posted.
      </AppText>
      <Pressable
        accessible
        accessibilityLabel={`Apply to Payback, selected ${selectedLabel}`}
        accessibilityRole="button"
        onPress={() => setOpen(true)}
        style={({ pressed }) => [
          styles.paybackSelect,
          { backgroundColor: colors.surfaceElevated, borderColor: colors.border },
          pressed && styles.pressed,
        ]}
      >
        <View style={styles.paybackSelectText}>
          <AppText numberOfLines={1} variant="label">
            {selectedLabel}
          </AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {loading ? 'Loading Paybacks...' : 'Choose Payback'}
          </AppText>
        </View>
        <ChevronDown color={colors.text} size={20} />
      </Pressable>
      <Modal animationType="slide" onRequestClose={() => setOpen(false)} transparent visible={open}>
        <View style={styles.sheetBackdrop}>
          <View style={[styles.sheet, { backgroundColor: colors.background }]}>
            <View style={[styles.sheetHeader, { borderBottomColor: colors.border }]}>
              <View>
                <AppText variant="title">Apply to Payback</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  Posted entries reduce the selected Payback.
                </AppText>
              </View>
              <Pressable
                accessibilityLabel="Close Payback selector"
                onPress={() => setOpen(false)}
                style={styles.close}
              >
                <X color={colors.text} size={23} />
              </Pressable>
            </View>
            <ScrollView contentContainerStyle={styles.optionList}>
              <PaybackSelectOption
                label="No Payback"
                onPress={() => {
                  onChange('');
                  setOpen(false);
                }}
                selected={!value}
              />
              {loading ? (
                <View style={styles.selectorState}>
                  <ActivityIndicator color={colors.accent} />
                  <AppText style={{ color: colors.muted }} variant="caption">
                    Loading Paybacks...
                  </AppText>
                </View>
              ) : error ? (
                <View style={styles.selectorState}>
                  <AppText style={{ color: colors.danger }} variant="error">
                    {error}
                  </AppText>
                  {onRetry ? (
                    <Button icon={RefreshCw} label="Retry" onPress={onRetry} variant="secondary" />
                  ) : null}
                </View>
              ) : options.length ? (
                options.map((payback) => (
                  <PaybackSelectOption
                    key={payback.id}
                    label={payback.name}
                    onPress={() => {
                      onChange(payback.id);
                      setOpen(false);
                    }}
                    selected={value === payback.id}
                  />
                ))
              ) : (
                <View style={styles.selectorState}>
                  <AppText variant="label">No active Paybacks</AppText>
                  <AppText style={{ color: colors.muted, textAlign: 'center' }} variant="caption">
                    Create an active Payback before assigning entries.
                  </AppText>
                </View>
              )}
            </ScrollView>
          </View>
        </View>
      </Modal>
    </>
  );
}

function PaybackSelectOption({
  label,
  onPress,
  selected,
}: {
  label: string;
  onPress: () => void;
  selected: boolean;
}) {
  const { colors } = useAppTheme();
  return (
    <Pressable
      accessible
      accessibilityLabel={`Select ${label}`}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      onPress={onPress}
      style={({ pressed }) => [
        styles.paybackOption,
        {
          backgroundColor: selected ? colors.accentSoft : colors.surfaceElevated,
          borderColor: selected ? colors.accent : colors.border,
        },
        pressed && styles.pressed,
      ]}
    >
      <AppText
        numberOfLines={2}
        style={[styles.paybackOptionText, { color: selected ? colors.accent : colors.text }]}
        variant="label"
      >
        {label}
      </AppText>
      {selected ? <Check color={colors.accent} size={19} /> : null}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  checkbox: {
    alignItems: 'center',
    borderRadius: 4,
    borderWidth: 1,
    height: 22,
    justifyContent: 'center',
    width: 22,
  },
  checkboxRow: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 10,
    minHeight: 48,
    paddingHorizontal: 13,
    paddingVertical: 10,
  },
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  fieldGroup: { gap: 8 },
  form: { gap: 17, padding: 18, paddingBottom: 40 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 18,
  },
  screen: { flex: 1 },
  optionList: { gap: 10, padding: 16, paddingBottom: 32 },
  paybackOption: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 10,
    minHeight: 50,
    paddingHorizontal: 13,
    paddingVertical: 10,
  },
  paybackOptionText: { flex: 1 },
  paybackSelect: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 12,
    minHeight: 56,
    paddingHorizontal: 13,
    paddingVertical: 10,
  },
  paybackSelectText: { flex: 1, gap: 3 },
  pressed: { opacity: 0.74 },
  selectorState: { alignItems: 'center', gap: 10, minHeight: 120, justifyContent: 'center' },
  sheet: {
    borderTopLeftRadius: 8,
    borderTopRightRadius: 8,
    maxHeight: '78%',
    overflow: 'hidden',
  },
  sheetBackdrop: { flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(0,0,0,0.55)' },
  sheetHeader: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 16,
  },
});
