import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { Save } from 'lucide-react-native';
import { Controller, useForm } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { TextField } from '@/components/text-field';
import { parseMoneyToMinor } from '@/domain/money';
import { PaycheckFormValues, paycheckFormSchema, today } from '@/features/paychecks/form-schemas';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function NewPaycheckScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    setError,
  } = useForm<PaycheckFormValues>({
    resolver: zodResolver(paycheckFormSchema),
    defaultValues: { name: '', amount: '', incomeDate: today(), source: '', notes: '' },
  });

  const mutation = useMutation({
    mutationFn: (values: PaycheckFormValues) =>
      api.createPaycheck({
        name: values.name.trim(),
        amountMinor: parseMoneyToMinor(values.amount),
        incomeDate: values.incomeDate,
        source: values.source.trim() || null,
        notes: values.notes.trim() || null,
      }),
    onSuccess: async (paycheck) => {
      await queryClient.invalidateQueries({ queryKey: ['paychecks'] });
      router.replace(`/paychecks/${paycheck.id}`);
    },
  });

  async function submit(values: PaycheckFormValues) {
    try {
      await mutation.mutateAsync(values);
    } catch (error) {
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The paycheck was not created.'),
      });
    }
  }

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: 'New Paycheck',
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <ScrollScreen contentContainerStyle={styles.content}>
        <View style={styles.form}>
          <FormField control={control} error={errors.name?.message} label="Name" name="name" />
          <FormField
            control={control}
            error={errors.amount?.message}
            keyboardType="decimal-pad"
            label="Exact paycheck amount"
            name="amount"
          />
          <FormField
            control={control}
            error={errors.incomeDate?.message}
            label="Income date"
            name="incomeDate"
            placeholder="YYYY-MM-DD"
          />
          <FormField control={control} label="Source (optional)" name="source" />
          <FormField control={control} label="Notes (optional)" multiline name="notes" />
        </View>

        {errors.root?.message ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {errors.root.message}
          </AppText>
        ) : null}
        <Button
          icon={Save}
          label="Create paycheck"
          loading={isSubmitting || mutation.isPending}
          onPress={handleSubmit(submit)}
        />
      </ScrollScreen>
    </>
  );
}

function FormField({
  control,
  error,
  keyboardType,
  label,
  multiline,
  name,
  placeholder,
}: {
  control: ReturnType<typeof useForm<PaycheckFormValues>>['control'];
  error?: string;
  keyboardType?: 'decimal-pad';
  label: string;
  multiline?: boolean;
  name: keyof PaycheckFormValues;
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

const styles = StyleSheet.create({
  content: { gap: 20, paddingBottom: 36 },
  form: { gap: 16 },
});
