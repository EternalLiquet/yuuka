import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import {
  ArrowDown,
  ArrowUp,
  Check,
  Pencil,
  Plus,
  ReceiptText,
  Save,
  Trash2,
} from 'lucide-react-native';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
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
import {
  applicationEntriesFromDraft,
  draftEntriesFromRecurringBills,
  draftEntriesFromTemplate,
  draftTotalMinor,
  TemplateApplicationDraftEntry,
} from '@/features/templates/application-draft';
import { ImportRecurringBillsSheet } from '@/features/recurring-bills/import-recurring-bills-sheet';
import { TemplateEntryEditor } from '@/features/templates/template-entry-editor';
import type { TemplateEntryEditorEntry } from '@/features/templates/template-entry-editor';
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
  const [draftTemplateId, setDraftTemplateId] = useState('');
  const [draftEntries, setDraftEntries] = useState<TemplateApplicationDraftEntry[]>([]);
  const [editingDraftEntry, setEditingDraftEntry] = useState<TemplateApplicationDraftEntry | null>(
    null,
  );
  const [draftEditorVisible, setDraftEditorVisible] = useState(false);
  const [recurringImportVisible, setRecurringImportVisible] = useState(false);
  const submitInFlight = useRef(false);
  const [submitLocked, setSubmitLocked] = useState(false);
  const templatesQuery = useQuery({
    queryKey: ['templates'],
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
  const amountValue = useWatch({ control, name: 'amount' });
  const incomeDate = useWatch({ control, name: 'incomeDate' });
  const effectiveSelectedTemplateId =
    mode === 'template' ? selectedTemplateId || templatesQuery.data?.items[0]?.id || '' : '';
  const selectedTemplate = useMemo(
    () =>
      templatesQuery.data?.items.find((template) => template.id === effectiveSelectedTemplateId) ??
      null,
    [effectiveSelectedTemplateId, templatesQuery.data?.items],
  );
  const selectedTemplateIdentity = selectedTemplate?.id ?? '';
  useEffect(() => {
    if (mode !== 'template' || !selectedTemplate || draftTemplateId === selectedTemplate.id) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDraftEntries(draftEntriesFromTemplate(selectedTemplate));
    setDraftTemplateId(selectedTemplate.id);
  }, [draftTemplateId, mode, selectedTemplate, selectedTemplateIdentity]);

  const draftTotal = draftTotalMinor(draftEntries);
  const parsedAmount = parseAmountOrNull(amountValue);
  const differenceMinor = parsedAmount == null ? null : parsedAmount - draftTotal;
  const overAllocated = differenceMinor != null && differenceMinor < 0;

  const scratchMutation = useMutation({
    mutationFn: (values: PaycheckFormValues) => {
      const details = {
        name: values.name.trim(),
        amountMinor: parseMoneyToMinor(values.amount),
        incomeDate: values.incomeDate,
        source: values.source.trim() || null,
        notes: values.notes.trim() || null,
      };
      return draftEntries.length
        ? api.createPaycheckFromDraft({
            ...details,
            entries: applicationEntriesFromDraft(values.incomeDate, draftEntries),
          })
        : api.createPaycheck(details);
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
        entries: applicationEntriesFromDraft(values.incomeDate, draftEntries),
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
        const amountMinor = parseMoneyToMinor(values.amount);
        if (draftTotal > amountMinor) {
          throw new Error(
            `Template draft is over-allocated by ${formatMoney(
              draftTotal - amountMinor,
              settings.currencyCode,
            )}.`,
          );
        }
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
            onChange={(next) => {
              setMode(next);
              if (next === 'scratch') {
                setDraftEntries([]);
                setDraftTemplateId('');
              }
            }}
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

        {mode === 'scratch' || selectedTemplate ? (
          <TemplateDraft
            differenceMinor={differenceMinor}
            draftEntries={draftEntries}
            onAdd={() => {
              setEditingDraftEntry(null);
              setDraftEditorVisible(true);
            }}
            onImport={() => setRecurringImportVisible(true)}
            onEdit={(entry) => {
              setEditingDraftEntry(entry);
              setDraftEditorVisible(true);
            }}
            onMove={(index, offset) => {
              setDraftEntries((current) => moveDraftEntry(current, index, offset));
            }}
            onRemove={(clientId) => {
              setDraftEntries((current) => current.filter((entry) => entry.clientId !== clientId));
            }}
            onReset={
              selectedTemplate
                ? () => {
                    setDraftEntries(draftEntriesFromTemplate(selectedTemplate));
                    setDraftTemplateId(selectedTemplate.id);
                  }
                : undefined
            }
            totalMinor={draftTotal}
            title={mode === 'template' ? 'Template draft' : 'Paycheck draft'}
          />
        ) : null}

        {errors.root?.message ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {errors.root.message}
          </AppText>
        ) : null}
        <Button
          disabled={(mode === 'template' && !selectedTemplate) || overAllocated}
          icon={Save}
          label="Create paycheck"
          loading={loading}
          // eslint-disable-next-line react-hooks/refs
          onPress={handleSubmit(submit)}
        />
      </ScrollScreen>
      <TemplateEntryEditor
        entry={editingDraftEntry ? editorEntryFromDraft(editingDraftEntry) : null}
        onClose={() => setDraftEditorVisible(false)}
        onSubmit={(payload) => {
          const next: TemplateApplicationDraftEntry = {
            accountName: payload.accountName,
            amountMinor: payload.defaultAmountMinor,
            clientId: editingDraftEntry?.clientId ?? newDraftClientId(),
            defaultDueOffsetDays: payload.defaultDueOffsetDays,
            entryType: payload.entryType,
            name: payload.name,
            notes: payload.notes,
            payee: payload.payee,
            paymentMethod: payload.paymentMethod,
            sourceRecurringBillDefinitionId:
              payload.entryType === 'BILL'
                ? (editingDraftEntry?.sourceRecurringBillDefinitionId ?? null)
                : null,
            sourceRecurringOccurrenceDate:
              payload.entryType === 'BILL'
                ? (editingDraftEntry?.sourceRecurringOccurrenceDate ?? null)
                : null,
            targetDate: payload.targetDate,
            targetMinor: payload.targetMinor,
          };
          setDraftEntries((current) =>
            editingDraftEntry
              ? current.map((entry) =>
                  entry.clientId === editingDraftEntry.clientId ? next : entry,
                )
              : [...current, next],
          );
          return Promise.resolve();
        }}
        title={editingDraftEntry ? 'Edit draft entry' : 'New draft entry'}
        visible={draftEditorVisible}
      />
      <ImportRecurringBillsSheet
        incomeDate={incomeDate}
        localDraft
        onClose={() => setRecurringImportVisible(false)}
        onImport={(items) => {
          setDraftEntries((current) => [...current, ...draftEntriesFromRecurringBills(items)]);
          return Promise.resolve();
        }}
        visible={recurringImportVisible}
      />
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

function TemplateDraft({
  differenceMinor,
  draftEntries,
  onAdd,
  onEdit,
  onImport,
  onMove,
  onRemove,
  onReset,
  totalMinor,
  title,
}: {
  differenceMinor: number | null;
  draftEntries: TemplateApplicationDraftEntry[];
  onAdd: () => void;
  onEdit: (entry: TemplateApplicationDraftEntry) => void;
  onImport: () => void;
  onMove: (index: number, offset: number) => void;
  onRemove: (clientId: string) => void;
  onReset?: () => void;
  totalMinor: number;
  title: string;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const allocationMessage =
    differenceMinor == null
      ? 'Enter a paycheck amount to check allocation.'
      : differenceMinor === 0
        ? 'Fully allocated.'
        : differenceMinor > 0
          ? `${formatMoney(differenceMinor, settings.currencyCode)} left unallocated.`
          : `${formatMoney(Math.abs(differenceMinor), settings.currencyCode)} over-allocated.`;
  return (
    <View style={[styles.preview, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.previewHeader}>
        <View>
          <AppText variant="label">{title}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {draftEntries.length} editable entries before creation
          </AppText>
        </View>
        <AppText variant="money">{formatMoney(totalMinor, settings.currencyCode)}</AppText>
      </View>
      <AppText
        style={{
          color: differenceMinor != null && differenceMinor < 0 ? colors.danger : colors.muted,
        }}
        variant="caption"
      >
        {allocationMessage}
      </AppText>
      <View style={styles.actions}>
        <Button icon={Plus} label="Add draft entry" onPress={onAdd} variant="secondary" />
        <Button
          icon={ReceiptText}
          label="Import recurring Bills"
          onPress={onImport}
          variant="secondary"
        />
        {onReset ? <Button label="Reset from template" onPress={onReset} variant="ghost" /> : null}
      </View>
      {draftEntries.map((entry, index) => (
        <View key={entry.clientId} style={[styles.previewEntry, { borderTopColor: colors.border }]}>
          <View
            accessible
            accessibilityLabel={`Draft entry ${index + 1}: ${entry.name}, ${formatMoney(
              entry.amountMinor,
              settings.currencyCode,
            )}`}
            style={styles.templateOptionText}
          >
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
                  : 'Planned Savings'}
            </AppText>
          </View>
          <AppText variant="caption">
            {formatMoney(entry.amountMinor, settings.currencyCode)}
          </AppText>
          <View style={styles.draftActions}>
            <Button
              accessibilityLabel={`Move ${entry.name} up`}
              disabled={index === 0}
              icon={ArrowUp}
              label={`Move ${entry.name} up`}
              onPress={() => onMove(index, -1)}
              variant="ghost"
            />
            <Button
              accessibilityLabel={`Move ${entry.name} down`}
              disabled={index === draftEntries.length - 1}
              icon={ArrowDown}
              label={`Move ${entry.name} down`}
              onPress={() => onMove(index, 1)}
              variant="ghost"
            />
            <Button
              accessibilityLabel={`Edit ${entry.name}`}
              icon={Pencil}
              label={`Edit ${entry.name}`}
              onPress={() => onEdit(entry)}
              variant="ghost"
            />
            <Button
              accessibilityLabel={`Remove ${entry.name}`}
              icon={Trash2}
              label={`Remove ${entry.name}`}
              onPress={() => onRemove(entry.clientId)}
              variant="ghost"
            />
          </View>
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
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  content: { gap: 20, paddingBottom: 36 },
  draftActions: { alignItems: 'center', flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
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

function parseAmountOrNull(value: string) {
  try {
    return parseMoneyToMinor(value);
  } catch {
    return null;
  }
}

function moveDraftEntry(entries: TemplateApplicationDraftEntry[], index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= entries.length) return entries;
  const next = [...entries];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

function editorEntryFromDraft(entry: TemplateApplicationDraftEntry): TemplateEntryEditorEntry {
  return {
    accountName: entry.accountName,
    defaultAmountMinor: entry.amountMinor,
    defaultDueOffsetDays: entry.defaultDueOffsetDays,
    entryType: entry.entryType,
    id: entry.clientId,
    name: entry.name,
    notes: entry.notes,
    payee: entry.payee,
    paymentMethod: entry.paymentMethod,
    targetDate: entry.targetDate,
    targetMinor: entry.targetMinor,
  };
}

function newDraftClientId() {
  return `draft-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
