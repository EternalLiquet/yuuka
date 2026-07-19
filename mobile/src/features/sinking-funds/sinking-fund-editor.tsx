import { zodResolver } from '@hookform/resolvers/zod';
import { Save, X } from 'lucide-react-native';
import { useEffect } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { z } from 'zod';

import { SinkingFund } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { TextField } from '@/components/text-field';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const moneyOptional = z.string().superRefine((value, context) => {
  if (!value.trim()) return;
  try {
    parseMoneyToMinor(value);
  } catch (error) {
    context.addIssue({
      code: 'custom',
      message: error instanceof Error ? error.message : 'Enter a valid amount.',
    });
  }
});

const isoDateOptional = z
  .string()
  .refine((value) => !value || /^\d{4}-\d{2}-\d{2}$/.test(value), 'Use YYYY-MM-DD.');

const sinkingFundFormSchema = z.object({
  name: z.string().trim().min(1, 'Enter a Planned Savings name.').max(160),
  target: moneyOptional,
  targetDate: isoDateOptional,
  openingBalance: moneyOptional,
  notes: z.string().max(2000),
});

type SinkingFundFormValues = z.infer<typeof sinkingFundFormSchema>;

export function SinkingFundEditor({
  onClose,
  onSubmit,
  sinkingFund,
}: {
  onClose: () => void;
  onSubmit: (values: {
    name: string;
    notes: string | null;
    openingBalanceMinor?: number | null;
    targetDate: string | null;
    targetMinor: number | null;
  }) => Promise<void>;
  sinkingFund?: SinkingFund | null;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    reset,
    setError,
  } = useForm<SinkingFundFormValues>({
    resolver: zodResolver(sinkingFundFormSchema),
    defaultValues: defaults(sinkingFund),
  });

  useEffect(() => {
    reset(defaults(sinkingFund));
  }, [reset, sinkingFund]);

  async function submit(values: SinkingFundFormValues) {
    try {
      await onSubmit({
        name: values.name.trim(),
        targetMinor: values.target.trim() ? parseMoneyToMinor(values.target) : null,
        targetDate: values.targetDate || null,
        openingBalanceMinor:
          !sinkingFund && values.openingBalance.trim()
            ? parseMoneyToMinor(values.openingBalance)
            : null,
        notes: values.notes.trim() || null,
      });
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'Planned Savings was not saved.'),
      });
    }
  }

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <View style={[styles.header, { borderBottomColor: colors.border }]}>
        <View>
          <AppText variant="title">
            {sinkingFund ? 'Edit Planned Savings' : 'New Planned Savings'}
          </AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            Contributions apply when linked paycheck entries reach Posted.
          </AppText>
        </View>
        <Pressable
          accessibilityLabel="Close Planned Savings editor"
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
        <ControlledField control={control} error={errors.name?.message} label="Name" name="name" />
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
        {!sinkingFund ? (
          <ControlledField
            control={control}
            error={errors.openingBalance?.message}
            keyboardType="decimal-pad"
            label="Opening balance (optional)"
            name="openingBalance"
          />
        ) : (
          <View style={[styles.derivedMetric, { borderColor: colors.border }]}>
            <AppText style={{ color: colors.muted }} variant="caption">
              Current balance
            </AppText>
            <AppText variant="label">
              {formatMoney(sinkingFund.currentBalanceMinor, settings.currencyCode)}
            </AppText>
          </View>
        )}
        <ControlledField control={control} label="Notes (optional)" multiline name="notes" />
        {errors.root?.message ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {errors.root.message}
          </AppText>
        ) : null}
        <Button
          icon={Save}
          label={sinkingFund ? 'Save Planned Savings' : 'Create Planned Savings'}
          loading={isSubmitting}
          onPress={handleSubmit(submit)}
        />
      </ScrollView>
    </View>
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
  control: ReturnType<typeof useForm<SinkingFundFormValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad';
  label: string;
  multiline?: boolean;
  name: keyof SinkingFundFormValues;
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

function defaults(sinkingFund?: SinkingFund | null): SinkingFundFormValues {
  return {
    name: sinkingFund?.name ?? '',
    target: sinkingFund?.targetMinor == null ? '' : minorToInput(sinkingFund.targetMinor),
    targetDate: sinkingFund?.targetDate ?? '',
    openingBalance: '',
    notes: sinkingFund?.notes ?? '',
  };
}

const styles = StyleSheet.create({
  close: { alignItems: 'center', height: 44, justifyContent: 'center', width: 44 },
  derivedMetric: { borderRadius: 8, borderWidth: 1, gap: 4, padding: 12 },
  form: { gap: 13, padding: 18, paddingBottom: 40 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 18,
  },
  screen: { flex: 1 },
});
