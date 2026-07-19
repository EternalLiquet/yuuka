import { zodResolver } from '@hookform/resolvers/zod';
import { Check, Save, Trash2, X } from 'lucide-react-native';
import { useEffect, useRef, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Alert, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { EntryPaymentMethod, TemplateEntry } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { SegmentedControl } from '@/components/segmented-control';
import { TextField } from '@/components/text-field';
import { minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

import { TemplateEntryFormValues, templateEntryFormSchema } from './form-schemas';

export type TemplateEntryEditorEntry = Pick<
  TemplateEntry,
  | 'accountName'
  | 'defaultAmountMinor'
  | 'defaultDueOffsetDays'
  | 'entryType'
  | 'id'
  | 'name'
  | 'notes'
  | 'payee'
  | 'paymentMethod'
  | 'targetDate'
  | 'targetMinor'
>;

const typeOptions = [
  { label: 'Bill', value: 'BILL' },
  { label: 'Spending Bucket', value: 'SPENDING_BUCKET' },
  { label: 'Planned Savings', value: 'SINKING_FUND' },
] as const;

export function TemplateEntryEditor({
  entry,
  onClose,
  onDelete,
  onSubmit,
  title,
  visible,
}: {
  entry?: TemplateEntryEditorEntry | null;
  onClose: () => void;
  onDelete?: () => Promise<void>;
  onSubmit: (values: {
    accountName: string | null;
    defaultAmountMinor: number;
    defaultDueOffsetDays: number | null;
    entryType: TemplateEntryFormValues['entryType'];
    name: string;
    notes: string | null;
    paymentMethod: EntryPaymentMethod | null;
    payee: string | null;
    targetDate: string | null;
    targetMinor: number | null;
  }) => Promise<void>;
  title?: string;
  visible: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const deleteInFlight = useRef(false);
  const [deletePending, setDeletePending] = useState(false);
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    reset,
    setError,
    setValue,
  } = useForm<TemplateEntryFormValues>({
    resolver: zodResolver(templateEntryFormSchema),
    defaultValues: defaults(entry),
  });
  const entryType = useWatch({ control, name: 'entryType' });

  useEffect(() => {
    if (visible) reset(defaults(entry));
  }, [entry, reset, visible]);

  useEffect(() => {
    if (entryType !== 'BILL') {
      setValue('manualPay', false);
      setValue('defaultDueOffsetDays', '');
    }
  }, [entryType, setValue]);

  async function submit(values: TemplateEntryFormValues) {
    try {
      await onSubmit({
        entryType: values.entryType,
        name: values.name.trim(),
        defaultAmountMinor: parseMoneyToMinor(values.amount),
        paymentMethod:
          values.entryType === 'BILL' ? (values.manualPay ? 'MANUAL' : 'AUTOPAY') : null,
        defaultDueOffsetDays:
          values.entryType === 'BILL' && values.defaultDueOffsetDays
            ? Number(values.defaultDueOffsetDays)
            : null,
        accountName:
          values.entryType === 'BILL' && values.accountName.trim()
            ? values.accountName.trim()
            : null,
        payee: values.entryType === 'BILL' && values.payee.trim() ? values.payee.trim() : null,
        notes: values.notes.trim() || null,
        targetMinor:
          values.entryType === 'SINKING_FUND' && values.target
            ? parseMoneyToMinor(values.target)
            : null,
        targetDate:
          values.entryType === 'SINKING_FUND' && values.targetDate ? values.targetDate : null,
      });
      onClose();
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The template entry was not saved.'),
      });
    }
  }

  function confirmDelete() {
    if (!entry) return;
    Alert.alert(
      'Delete template entry?',
      `Remove "${entry.name}" from this template? Existing paychecks created from this template will not change.`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Delete',
          style: 'destructive',
          onPress: () => {
            void remove();
          },
        },
      ],
    );
  }

  async function remove() {
    if (!onDelete || deleteInFlight.current || deletePending) return;

    deleteInFlight.current = true;
    setDeletePending(true);
    try {
      await onDelete();
      onClose();
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The template entry was not deleted.'),
      });
    } finally {
      deleteInFlight.current = false;
      setDeletePending(false);
    }
  }

  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={visible}>
      <View style={[styles.screen, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <View>
            <AppText variant="title">
              {title ?? (entry ? 'Edit template entry' : 'New template entry')}
            </AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {entry?.name ?? 'Reusable allocation'}
            </AppText>
          </View>
          <Pressable
            accessibilityLabel="Close template entry editor"
            onPress={onClose}
            style={styles.close}
          >
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
                  label="Template entry type"
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
                error={errors.defaultDueOffsetDays?.message}
                keyboardType="numeric"
                label="Due offset days (optional)"
                name="defaultDueOffsetDays"
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
          <ControlledField control={control} label="Notes (optional)" multiline name="notes" />
          {errors.root?.message ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {errors.root.message}
            </AppText>
          ) : null}
          <Button
            icon={Save}
            label="Save template entry"
            loading={isSubmitting}
            onPress={handleSubmit(submit)}
          />
          {entry && onDelete ? (
            <Button
              icon={Trash2}
              label="Delete template entry"
              loading={deletePending}
              onPress={confirmDelete}
              variant="danger"
            />
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
  control: ReturnType<typeof useForm<TemplateEntryFormValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad' | 'numeric';
  label: string;
  multiline?: boolean;
  name: keyof TemplateEntryFormValues;
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

function defaults(entry?: TemplateEntryEditorEntry | null): TemplateEntryFormValues {
  return {
    entryType: entry?.entryType ?? 'BILL',
    name: entry?.name ?? '',
    amount: entry ? minorToInput(entry.defaultAmountMinor) : '',
    defaultDueOffsetDays:
      entry?.defaultDueOffsetDays == null ? '' : String(entry.defaultDueOffsetDays),
    manualPay: entry?.paymentMethod === 'MANUAL',
    accountName: entry?.accountName ?? '',
    payee: entry?.payee ?? '',
    notes: entry?.notes ?? '',
    target: entry?.targetMinor == null ? '' : minorToInput(entry.targetMinor),
    targetDate: entry?.targetDate ?? '',
  };
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
  pressed: { opacity: 0.74 },
  screen: { flex: 1 },
});
