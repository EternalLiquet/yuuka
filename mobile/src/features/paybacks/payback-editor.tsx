import { zodResolver } from '@hookform/resolvers/zod';
import { Save, X } from 'lucide-react-native';
import { useEffect, useRef } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { z } from 'zod';

import { displayError } from '@/api/display-error';
import { Payback } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { TextField } from '@/components/text-field';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const money = z.string().superRefine((value, context) => {
  try {
    parseMoneyToMinor(value);
  } catch (error) {
    context.addIssue({
      code: 'custom',
      message: error instanceof Error ? error.message : 'Enter a valid amount.',
    });
  }
});

const paybackFormSchema = z
  .object({
    name: z.string().trim().min(1, 'Enter a Payback name.').max(160),
    originalAmount: money,
    openingRemainingAmount: money,
    borrowedDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Use YYYY-MM-DD.'),
    source: z.string().max(160),
    notes: z.string().max(2000),
  })
  .superRefine((values, context) => {
    try {
      const original = parseMoneyToMinor(values.originalAmount);
      const opening = parseMoneyToMinor(values.openingRemainingAmount);
      if (original <= 0) {
        context.addIssue({
          code: 'custom',
          path: ['originalAmount'],
          message: 'Original amount must be greater than $0.00.',
        });
      }
      if (opening > original) {
        context.addIssue({
          code: 'custom',
          path: ['openingRemainingAmount'],
          message: 'Balance when tracking began cannot be greater than the original amount.',
        });
      }
    } catch {
      return;
    }
  });

type PaybackFormValues = z.infer<typeof paybackFormSchema>;

export function PaybackEditor({
  onClose,
  onSubmit,
  payback,
}: {
  onClose: () => void;
  onSubmit: (values: {
    borrowedDate: string;
    name: string;
    notes: string | null;
    openingRemainingAmountMinor: number;
    originalAmountMinor: number;
    source: string | null;
  }) => Promise<void>;
  payback?: Payback | null;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const openingEdited = useRef(Boolean(payback));
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    reset,
    setError,
    setValue,
  } = useForm<PaybackFormValues>({
    resolver: zodResolver(paybackFormSchema),
    defaultValues: defaults(payback),
  });
  useEffect(() => {
    reset(defaults(payback));
    openingEdited.current = Boolean(payback);
  }, [payback, reset]);

  async function submit(values: PaybackFormValues) {
    try {
      await onSubmit({
        borrowedDate: values.borrowedDate,
        name: values.name.trim(),
        originalAmountMinor: parseMoneyToMinor(values.originalAmount),
        openingRemainingAmountMinor: parseMoneyToMinor(values.openingRemainingAmount),
        source: values.source.trim() || null,
        notes: values.notes.trim() || null,
      });
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The Payback was not saved.'),
      });
    }
  }

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <View style={[styles.header, { borderBottomColor: colors.border }]}>
        <View>
          <AppText variant="title">{payback ? 'Edit Payback' : 'New Payback'}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            Track money you borrowed from yourself.
          </AppText>
        </View>
        <Pressable accessibilityLabel="Close Payback editor" onPress={onClose} style={styles.close}>
          <X color={colors.text} size={23} />
        </Pressable>
      </View>
      <ScrollView contentContainerStyle={styles.form} keyboardShouldPersistTaps="handled">
        <ControlledField control={control} error={errors.name?.message} label="Name" name="name" />
        <ControlledField
          control={control}
          error={errors.originalAmount?.message}
          keyboardType="decimal-pad"
          label="Original amount owed"
          name="originalAmount"
          onTextChanged={(value) => {
            if (!payback && !openingEdited.current) {
              setValue('openingRemainingAmount', value);
            }
          }}
        />
        <AppText style={{ color: colors.muted }} variant="caption">
          Original amount is the full amount you initially owed yourself.
        </AppText>
        <ControlledField
          control={control}
          error={errors.openingRemainingAmount?.message}
          keyboardType="decimal-pad"
          label="Balance when tracking began"
          name="openingRemainingAmount"
          onTextChanged={(_value) => {
            openingEdited.current = true;
          }}
        />
        <AppText style={{ color: colors.muted }} variant="caption">
          This is the historical balance before you started tracking this Payback in Yuuka. Current
          remaining is calculated from recorded repayments.
        </AppText>
        {payback ? (
          <View style={[styles.derivedMetric, { borderColor: colors.border }]}>
            <AppText style={{ color: colors.muted }} variant="caption">
              Current remaining
            </AppText>
            <AppText variant="label">
              {formatMoney(payback.remainingMinor, settings.currencyCode)}
            </AppText>
          </View>
        ) : null}
        <ControlledField
          control={control}
          error={errors.borrowedDate?.message}
          label="Borrowed or start date"
          name="borrowedDate"
          placeholder="YYYY-MM-DD"
        />
        <ControlledField control={control} label="Source or reason (optional)" name="source" />
        <ControlledField control={control} label="Notes (optional)" multiline name="notes" />
        {errors.root?.message ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {errors.root.message}
          </AppText>
        ) : null}
        <Button
          icon={Save}
          label={payback ? 'Save Payback' : 'Create Payback'}
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
  onTextChanged,
}: {
  control: ReturnType<typeof useForm<PaybackFormValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad';
  label: string;
  multiline?: boolean;
  name: keyof PaybackFormValues;
  placeholder?: string;
  onTextChanged?: (value: string) => void;
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
          onChangeText={(value) => {
            onTextChanged?.(value);
            field.onChange(value);
          }}
          placeholder={placeholder}
          value={String(field.value ?? '')}
        />
      )}
    />
  );
}

function defaults(payback?: Payback | null): PaybackFormValues {
  return {
    name: payback?.name ?? '',
    originalAmount: payback ? minorToInput(payback.originalAmountMinor) : '',
    openingRemainingAmount: payback ? minorToInput(payback.openingRemainingAmountMinor) : '',
    borrowedDate: payback?.borrowedDate ?? today(),
    source: payback?.source ?? '',
    notes: payback?.notes ?? '',
  };
}

function today() {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
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
