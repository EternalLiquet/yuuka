import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { Check, Save } from 'lucide-react-native';
import { useMemo, useRef, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Pressable, StyleSheet, View } from 'react-native';

import type { BudgetTemplate } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { SegmentedControl } from '@/components/segmented-control';
import { EmptyState, ErrorState, YuukaLoadingState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney, parseMoneyToMinor } from '@/domain/money';
import { PaycheckFormValues, paycheckFormSchema, today } from '@/features/paychecks/form-schemas';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const modeOptions = [
  { label: 'Start from scratch', value: 'scratch' },
  { label: 'Use a template', value: 'template' },
] as const;

export default function NewPaycheckScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [mode, setMode] = useState<'scratch' | 'template'>('scratch');
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const submitInFlight = useRef(false);
  const [submitLocked, setSubmitLocked] = useState(false);
  const templatesQuery = useQuery({
    queryKey: ['templates', 'paycheck-create'],
    queryFn: () => api.templates(false),
    enabled: mode === 'template',
  });
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    setError,
  } = useForm<PaycheckFormValues>({
    resolver: zodResolver(paycheckFormSchema),
    defaultValues: { name: '', amount: '', incomeDate: today(), source: '', notes: '' },
  });
  const effectiveSelectedTemplateId =
    mode === 'template' ? selectedTemplateId || templatesQuery.data?.items[0]?.id || '' : '';
  const selectedTemplate = useMemo(
    () =>
      templatesQuery.data?.items.find((template) => template.id === effectiveSelectedTemplateId) ??
      null,
    [effectiveSelectedTemplateId, templatesQuery.data?.items],
  );

  const scratchMutation = useMutation({
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
    onSettled: () => {
      submitInFlight.current = false;
      setSubmitLocked(false);
    },
  });
  const templateMutation = useMutation({
    mutationFn: (values: PaycheckFormValues) => {
      if (!selectedTemplate) throw new Error('Choose a template first.');
      return api.createPaycheckFromTemplate({
        templateId: selectedTemplate.id,
        name: values.name.trim(),
        amountMinor: parseMoneyToMinor(values.amount),
        incomeDate: values.incomeDate,
        source: values.source.trim() || null,
        notes: values.notes.trim() || null,
      });
    },
    onSuccess: async (paycheck) => {
      await queryClient.invalidateQueries({ queryKey: ['paychecks'] });
      router.replace(`/paychecks/${paycheck.id}`);
    },
    onSettled: () => {
      submitInFlight.current = false;
      setSubmitLocked(false);
    },
  });

  async function submit(values: PaycheckFormValues) {
    if (
      submitInFlight.current ||
      submitLocked ||
      scratchMutation.isPending ||
      templateMutation.isPending
    )
      return;
    submitInFlight.current = true;
    setSubmitLocked(true);
    try {
      if (mode === 'template') {
        await templateMutation.mutateAsync(values);
      } else {
        await scratchMutation.mutateAsync(values);
      }
    } catch (error) {
      submitInFlight.current = false;
      setSubmitLocked(false);
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The paycheck was not created.'),
      });
    }
  }

  const loading =
    isSubmitting || submitLocked || scratchMutation.isPending || templateMutation.isPending;

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
        <View style={styles.fieldGroup}>
          <AppText variant="label">Creation mode</AppText>
          <SegmentedControl
            label="Paycheck creation mode"
            onChange={setMode}
            options={modeOptions}
            value={mode}
          />
        </View>

        {mode === 'template' ? (
          <TemplatePicker
            error={
              templatesQuery.isError
                ? displayError(
                    templatesQuery.error,
                    settings.currencyCode,
                    'Templates could not be loaded.',
                  )
                : null
            }
            loading={templatesQuery.isPending}
            onRetry={() => templatesQuery.refetch()}
            onSelect={setSelectedTemplateId}
            selectedTemplateId={effectiveSelectedTemplateId}
            templates={templatesQuery.data?.items ?? []}
          />
        ) : null}

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

        {mode === 'template' && selectedTemplate ? (
          <TemplatePreview template={selectedTemplate} />
        ) : null}

        {errors.root?.message ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {errors.root.message}
          </AppText>
        ) : null}
        <Button
          disabled={mode === 'template' && !selectedTemplate}
          icon={Save}
          label="Create paycheck"
          loading={loading}
          // eslint-disable-next-line react-hooks/refs
          onPress={handleSubmit(submit)}
        />
      </ScrollScreen>
    </>
  );
}

function TemplatePicker({
  error,
  loading,
  onRetry,
  onSelect,
  selectedTemplateId,
  templates,
}: {
  error: string | null;
  loading: boolean;
  onRetry: () => void;
  onSelect: (id: string) => void;
  selectedTemplateId: string;
  templates: BudgetTemplate[];
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  if (loading)
    return <YuukaLoadingState message="Loading templates..." minHeight={160} size={60} />;
  if (error) return <ErrorState message={error} retry={onRetry} />;
  if (!templates.length) {
    return (
      <EmptyState
        mascot="clipboard"
        message="Create a template before starting a paycheck from one."
        title="No templates available"
      />
    );
  }
  return (
    <View style={styles.fieldGroup}>
      <AppText variant="label">Template</AppText>
      {templates.map((template) => {
        const selected = template.id === selectedTemplateId;
        return (
          <Pressable
            accessibilityLabel={`Select template ${template.name}`}
            accessibilityRole="button"
            accessibilityState={{ selected }}
            key={template.id}
            onPress={() => onSelect(template.id)}
            style={({ pressed }) => [
              styles.templateOption,
              {
                backgroundColor: selected ? colors.accentSoft : colors.surfaceElevated,
                borderColor: selected ? colors.accent : colors.border,
              },
              pressed && styles.pressed,
            ]}
          >
            <View style={styles.templateOptionText}>
              <AppText variant="label">{template.name}</AppText>
              <AppText style={{ color: colors.muted }} variant="caption">
                {template.entryCount} {template.entryCount === 1 ? 'entry' : 'entries'} |{' '}
                {formatMoney(template.defaultTotalMinor, settings.currencyCode)}
              </AppText>
            </View>
            {selected ? <Check color={colors.accent} size={20} /> : null}
          </Pressable>
        );
      })}
    </View>
  );
}

function TemplatePreview({ template }: { template: BudgetTemplate }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const entries = [...template.entries].sort((left, right) => left.position - right.position);
  return (
    <View style={[styles.preview, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.previewHeader}>
        <View>
          <AppText variant="label">Template preview</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {template.entryCount} entries copied as independent snapshots
          </AppText>
        </View>
        <AppText variant="money">
          {formatMoney(template.defaultTotalMinor, settings.currencyCode)}
        </AppText>
      </View>
      {entries.map((entry) => (
        <View key={entry.id} style={[styles.previewEntry, { borderTopColor: colors.border }]}>
          <View style={styles.templateOptionText}>
            <AppText numberOfLines={1} variant="caption">
              {entry.name}
            </AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {entry.entryType === 'BILL'
                ? entry.paymentMethod === 'MANUAL'
                  ? 'Bill | Manual Pay'
                  : 'Bill | Autopay'
                : entry.entryType === 'SPENDING_BUCKET'
                  ? 'Spending Bucket'
                  : 'Sinking Fund'}
            </AppText>
          </View>
          <AppText variant="caption">
            {formatMoney(entry.defaultAmountMinor, settings.currencyCode)}
          </AppText>
        </View>
      ))}
    </View>
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
  fieldGroup: { gap: 10 },
  form: { gap: 16 },
  pressed: { opacity: 0.74 },
  preview: { borderRadius: 8, borderWidth: 1, gap: 2, padding: 14 },
  previewEntry: {
    alignItems: 'center',
    borderTopWidth: 1,
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
    paddingTop: 10,
  },
  previewHeader: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
    paddingBottom: 8,
  },
  templateOption: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 10,
    minHeight: 56,
    paddingHorizontal: 13,
    paddingVertical: 10,
  },
  templateOptionText: { flex: 1, gap: 3 },
});
