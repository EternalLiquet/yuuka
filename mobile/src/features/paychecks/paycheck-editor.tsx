import { zodResolver } from '@hookform/resolvers/zod';
import { Save, X } from 'lucide-react-native';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import { Paycheck } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { TextField } from '@/components/text-field';
import { minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useAppTheme } from '@/theme/use-app-theme';

import { PaycheckFormValues, paycheckFormSchema } from './form-schemas';

export function PaycheckEditor({
  onClose,
  onSubmit,
  paycheck,
  visible,
}: {
  onClose: () => void;
  onSubmit: (values: {
    amountMinor: number;
    incomeDate: string;
    name: string;
    notes: string | null;
    source: string | null;
    version: number;
  }) => Promise<void>;
  paycheck: Paycheck;
  visible: boolean;
}) {
  const { colors } = useAppTheme();
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    reset,
    setError,
  } = useForm<PaycheckFormValues>({
    resolver: zodResolver(paycheckFormSchema),
    defaultValues: defaults(paycheck),
  });
  useEffect(() => {
    if (visible) reset(defaults(paycheck));
  }, [paycheck, reset, visible]);

  async function submit(values: PaycheckFormValues) {
    try {
      await onSubmit({
        name: values.name.trim(),
        amountMinor: parseMoneyToMinor(values.amount),
        incomeDate: values.incomeDate,
        source: values.source.trim() || null,
        notes: values.notes.trim() || null,
        version: paycheck.version,
      });
      onClose();
    } catch (error) {
      setError('root', {
        message: error instanceof Error ? error.message : 'The paycheck was not saved.',
      });
    }
  }

  return (
    <Modal animationType="slide" onRequestClose={onClose} visible={visible}>
      <View style={[styles.screen, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <AppText variant="title">Edit paycheck</AppText>
          <Pressable
            accessibilityLabel="Close paycheck editor"
            onPress={onClose}
            style={styles.close}
          >
            <X color={colors.text} size={23} />
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={styles.form} keyboardShouldPersistTaps="handled">
          <Field control={control} error={errors.name?.message} label="Name" name="name" />
          <Field
            control={control}
            error={errors.amount?.message}
            keyboardType="decimal-pad"
            label="Exact paycheck amount"
            name="amount"
          />
          <Field
            control={control}
            error={errors.incomeDate?.message}
            label="Income date"
            name="incomeDate"
          />
          <Field control={control} label="Source (optional)" name="source" />
          <Field control={control} label="Notes (optional)" multiline name="notes" />
          {errors.root?.message ? (
            <AppText style={{ color: colors.danger }} variant="error">
              {errors.root.message}
            </AppText>
          ) : null}
          <Button
            icon={Save}
            label="Save paycheck"
            loading={isSubmitting}
            onPress={handleSubmit(submit)}
          />
        </ScrollView>
      </View>
    </Modal>
  );
}

function Field({
  control,
  error,
  keyboardType,
  label,
  multiline,
  name,
}: {
  control: ReturnType<typeof useForm<PaycheckFormValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad';
  label: string;
  multiline?: boolean;
  name: keyof PaycheckFormValues;
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
          value={String(field.value ?? '')}
        />
      )}
    />
  );
}

function defaults(paycheck: Paycheck): PaycheckFormValues {
  return {
    name: paycheck.name,
    amount: minorToInput(paycheck.amountMinor),
    incomeDate: paycheck.incomeDate,
    source: paycheck.source ?? '',
    notes: paycheck.notes ?? '',
  };
}

const styles = StyleSheet.create({
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  form: { gap: 16, padding: 18, paddingBottom: 36 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 18,
  },
  screen: { flex: 1 },
});
