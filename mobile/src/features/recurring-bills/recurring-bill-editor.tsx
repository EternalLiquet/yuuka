import { zodResolver } from '@hookform/resolvers/zod';
import { Check, Save } from 'lucide-react-native';
import { Controller, useForm } from 'react-hook-form';
import { Pressable, StyleSheet, View } from 'react-native';
import { z } from 'zod';

import type { RecurringBill } from '@/api/contracts';
import type { RecurringBillPayload } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { TextField } from '@/components/text-field';
import { minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useAppTheme } from '@/theme/use-app-theme';

const formSchema = z.object({
  accountName: z.string().max(160),
  amount: z.string().refine(canParseMoney, 'Enter a valid typical amount.'),
  dueDay: z
    .string()
    .regex(/^\d+$/, 'Enter a due day from 1 through 31.')
    .refine((value) => Number(value) >= 1 && Number(value) <= 31, 'Use a day from 1 through 31.'),
  manualPay: z.boolean(),
  name: z.string().trim().min(1, 'Enter a name.').max(160),
  notes: z.string().max(2000),
  payee: z.string().max(160),
});
type FormValues = z.infer<typeof formSchema>;

export function RecurringBillEditor({
  definition,
  onSubmit,
  submitLabel,
}: {
  definition?: RecurringBill;
  onSubmit: (payload: RecurringBillPayload & { version?: number }) => Promise<void>;
  submitLabel: string;
}) {
  const { colors } = useAppTheme();
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    setError,
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: defaults(definition),
  });

  async function submit(values: FormValues) {
    try {
      await onSubmit({
        name: values.name.trim(),
        typicalAmountMinor: parseMoneyToMinor(values.amount),
        paymentMethod: values.manualPay ? 'MANUAL' : 'AUTOPAY',
        dueDay: Number(values.dueDay),
        accountName: values.accountName.trim() || null,
        payee: values.payee.trim() || null,
        notes: values.notes.trim() || null,
        version: definition?.version,
      });
    } catch (error) {
      setError('root', {
        message: error instanceof Error ? error.message : 'The recurring Bill was not saved.',
      });
    }
  }

  return (
    <ScrollScreen contentContainerStyle={styles.content}>
      <Field control={control} error={errors.name?.message} label="Name" name="name" />
      <Field
        control={control}
        error={errors.amount?.message}
        keyboardType="decimal-pad"
        label="Typical amount"
        name="amount"
      />
      <Field
        control={control}
        error={errors.dueDay?.message}
        keyboardType="number-pad"
        label="Monthly due day"
        name="dueDay"
      />
      <Field control={control} label="Account (optional)" name="accountName" />
      <Field control={control} label="Payee (optional)" name="payee" />
      <Controller
        control={control}
        name="manualPay"
        render={({ field }) => (
          <Pressable
            accessibilityLabel="I need to pay this manually"
            accessibilityRole="checkbox"
            accessibilityState={{ checked: field.value }}
            onPress={() => field.onChange(!field.value)}
            style={[
              styles.checkboxRow,
              { backgroundColor: colors.surface, borderColor: colors.border },
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
      <Field control={control} label="Notes (optional)" multiline name="notes" />
      <AppText style={{ color: colors.muted }} variant="caption">
        A due day beyond a month&apos;s length uses that month&apos;s final day.
      </AppText>
      {errors.root?.message ? (
        <AppText style={{ color: colors.danger }} variant="error">
          {errors.root.message}
        </AppText>
      ) : null}
      <Button
        icon={Save}
        label={submitLabel}
        loading={isSubmitting}
        onPress={handleSubmit(submit)}
      />
    </ScrollScreen>
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
  control: ReturnType<typeof useForm<FormValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad' | 'number-pad';
  label: string;
  multiline?: boolean;
  name: keyof FormValues;
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

function defaults(definition?: RecurringBill): FormValues {
  return {
    accountName: definition?.accountName ?? '',
    amount: definition ? minorToInput(definition.typicalAmountMinor) : '',
    dueDay: String(definition?.dueDay ?? ''),
    manualPay: definition?.paymentMethod === 'MANUAL',
    name: definition?.name ?? '',
    notes: definition?.notes ?? '',
    payee: definition?.payee ?? '',
  };
}

function canParseMoney(value: string) {
  try {
    parseMoneyToMinor(value);
    return true;
  } catch {
    return false;
  }
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
  },
  content: { gap: 16, paddingBottom: 36 },
});
