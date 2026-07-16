import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { ArrowDown, ArrowLeft, ArrowUp, Pencil, Plus, Save, Trash2 } from 'lucide-react-native';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { PaycheckFormValues, paycheckFormSchema, today } from '@/features/paychecks/form-schemas';
import {
  applicationEntriesFromDraft,
  draftEntriesFromPaycheck,
  draftTotalMinor,
  TemplateApplicationDraftEntry,
} from '@/features/templates/application-draft';
import { TemplateEntryEditor } from '@/features/templates/template-entry-editor';
import type { TemplateEntryEditorEntry } from '@/features/templates/template-entry-editor';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function DuplicatePaycheckScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const initializedSourceId = useRef('');
  const submitInFlight = useRef(false);
  const [step, setStep] = useState<'details' | 'entries'>('details');
  const [draftEntries, setDraftEntries] = useState<TemplateApplicationDraftEntry[]>([]);
  const [clearedPaybackCount, setClearedPaybackCount] = useState(0);
  const [omittedLeftoverCount, setOmittedLeftoverCount] = useState(0);
  const [editingDraftEntry, setEditingDraftEntry] = useState<TemplateApplicationDraftEntry | null>(
    null,
  );
  const [draftEditorVisible, setDraftEditorVisible] = useState(false);
  const [submitLocked, setSubmitLocked] = useState(false);
  const query = useQuery({ queryKey: ['paycheck', id], queryFn: () => api.paycheck(id) });
  const {
    control,
    formState: { errors, isSubmitting },
    handleSubmit,
    reset,
    setError,
  } = useForm<PaycheckFormValues>({
    resolver: zodResolver(paycheckFormSchema),
    defaultValues: { name: '', amount: '', incomeDate: today(), source: '', notes: '' },
  });
  const amountValue = useWatch({ control, name: 'amount' });
  const incomeDate = useWatch({ control, name: 'incomeDate' });

  useEffect(() => {
    if (!query.data || initializedSourceId.current === query.data.id) return;
    const draft = draftEntriesFromPaycheck(query.data);
    initializedSourceId.current = query.data.id;
    reset({
      name: query.data.name,
      amount: minorToInput(query.data.amountMinor),
      incomeDate: today(),
      source: query.data.source ?? '',
      notes: query.data.notes ?? '',
    });
    setDraftEntries(draft.entries);
    setClearedPaybackCount(draft.clearedPaybackCount);
    setOmittedLeftoverCount(draft.omittedLeftoverCount);
  }, [query.data, reset]);

  const draftTotal = draftTotalMinor(draftEntries);
  const parsedAmount = parseAmountOrNull(amountValue);
  const differenceMinor = parsedAmount == null ? null : parsedAmount - draftTotal;
  const overAllocated = differenceMinor != null && differenceMinor < 0;

  const mutation = useMutation({
    mutationFn: (values: PaycheckFormValues) =>
      api.createPaycheckFromDraft({
        name: values.name.trim(),
        amountMinor: parseMoneyToMinor(values.amount),
        incomeDate: values.incomeDate,
        source: values.source.trim() || null,
        notes: values.notes.trim() || null,
        entries: applicationEntriesFromDraft(values.incomeDate, draftEntries),
      }),
    onSuccess: async (paycheck) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['paychecks'] }),
        queryClient.invalidateQueries({ queryKey: ['paycheck', id] }),
        queryClient.invalidateQueries({ queryKey: ['paycheck', paycheck.id] }),
        queryClient.invalidateQueries({ queryKey: ['search', 'entries'] }),
        queryClient.invalidateQueries({ queryKey: ['spending-buckets'] }),
      ]);
      router.replace(`/paychecks/${paycheck.id}`);
    },
    onSettled: () => {
      submitInFlight.current = false;
      setSubmitLocked(false);
    },
  });

  const createDisabled = overAllocated || isSubmitting || submitLocked || mutation.isPending;
  const sourceName = query.data?.name ?? 'paycheck';

  async function continueToEntries(values: PaycheckFormValues) {
    parseMoneyToMinor(values.amount);
    setStep('entries');
  }

  async function create(values: PaycheckFormValues) {
    if (submitInFlight.current || createDisabled) return;
    submitInFlight.current = true;
    setSubmitLocked(true);
    try {
      const amountMinor = parseMoneyToMinor(values.amount);
      if (draftTotal > amountMinor) {
        throw new Error(
          `Draft is over-allocated by ${formatMoney(draftTotal - amountMinor, settings.currencyCode)}.`,
        );
      }
      await mutation.mutateAsync(values);
    } catch (error) {
      submitInFlight.current = false;
      setSubmitLocked(false);
      setError('root', {
        message: displayError(error, settings.currencyCode, 'The paycheck was not created.'),
      });
    }
  }

  const draftSummary = useMemo(
    () => ({
      allocation:
        differenceMinor == null
          ? 'Enter a paycheck amount to check allocation.'
          : differenceMinor === 0
            ? 'Fully allocated.'
            : differenceMinor > 0
              ? `${formatMoney(differenceMinor, settings.currencyCode)} left unallocated.`
              : `${formatMoney(Math.abs(differenceMinor), settings.currencyCode)} over-allocated.`,
      tone: differenceMinor != null && differenceMinor < 0 ? colors.danger : colors.muted,
    }),
    [colors.danger, colors.muted, differenceMinor, settings.currencyCode],
  );

  if (query.isPending && !query.data) {
    return (
      <ScrollScreen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading paycheck..." />
      </ScrollScreen>
    );
  }

  if (query.isError && !query.data) {
    return (
      <ScrollScreen contentContainerStyle={styles.center}>
        <ErrorState
          message={displayError(
            query.error,
            settings.currencyCode,
            'Paycheck could not be loaded.',
          )}
          retry={() => query.refetch()}
        />
      </ScrollScreen>
    );
  }

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: `Duplicate ${sourceName}`,
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <ScrollScreen contentContainerStyle={styles.content}>
        <View style={styles.stepHeader}>
          <AppText variant="title">Duplicate Paycheck</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {step === 'details'
              ? 'Review paycheck details first.'
              : 'Adjust entries before creating.'}
          </AppText>
        </View>

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

        {step === 'details' ? (
          <Button
            icon={ArrowDown}
            label="Continue to entries"
            onPress={handleSubmit(continueToEntries)}
          />
        ) : (
          <>
            <DraftSummary
              clearedPaybackCount={clearedPaybackCount}
              differenceMessage={draftSummary.allocation}
              differenceTone={draftSummary.tone}
              draftEntries={draftEntries}
              incomeDate={incomeDate}
              omittedLeftoverCount={omittedLeftoverCount}
              onAdd={() => {
                setEditingDraftEntry(null);
                setDraftEditorVisible(true);
              }}
              onEdit={(entry) => {
                setEditingDraftEntry(entry);
                setDraftEditorVisible(true);
              }}
              onMove={(index, offset) => {
                setDraftEntries((current) => moveDraftEntry(current, index, offset));
              }}
              onRemove={(clientId) => {
                setDraftEntries((current) =>
                  current.filter((entry) => entry.clientId !== clientId),
                );
              }}
              totalMinor={draftTotal}
            />
            <View style={styles.actions}>
              <Button
                icon={ArrowLeft}
                label="Back to details"
                onPress={() => setStep('details')}
                variant="secondary"
              />
              <Button
                disabled={createDisabled}
                icon={Save}
                label="Create paycheck"
                loading={submitLocked || mutation.isPending}
                // eslint-disable-next-line react-hooks/refs
                onPress={handleSubmit(create)}
              />
            </View>
          </>
        )}

        {errors.root?.message ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {errors.root.message}
          </AppText>
        ) : null}
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
    </>
  );
}

function DraftSummary({
  clearedPaybackCount,
  differenceMessage,
  differenceTone,
  draftEntries,
  incomeDate,
  omittedLeftoverCount,
  onAdd,
  onEdit,
  onMove,
  onRemove,
  totalMinor,
}: {
  clearedPaybackCount: number;
  differenceMessage: string;
  differenceTone: string;
  draftEntries: TemplateApplicationDraftEntry[];
  incomeDate: string;
  omittedLeftoverCount: number;
  onAdd: () => void;
  onEdit: (entry: TemplateApplicationDraftEntry) => void;
  onMove: (index: number, offset: number) => void;
  onRemove: (clientId: string) => void;
  totalMinor: number;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  return (
    <View style={[styles.preview, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.previewHeader}>
        <View style={styles.previewTitle}>
          <AppText variant="label">Draft entries</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {draftEntries.length} entries copied locally before creation
          </AppText>
        </View>
        <AppText variant="money">{formatMoney(totalMinor, settings.currencyCode)}</AppText>
      </View>
      <AppText style={{ color: differenceTone }} variant="caption">
        {differenceMessage}
      </AppText>
      {clearedPaybackCount > 0 ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          {clearedPaybackCount} Payback assignment{clearedPaybackCount === 1 ? '' : 's'}{' '}
          {clearedPaybackCount === 1 ? 'was' : 'were'} not copied.
        </AppText>
      ) : null}
      {omittedLeftoverCount > 0 ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          {omittedLeftoverCount} LEFTOVER entr{omittedLeftoverCount === 1 ? 'y was' : 'ies were'}{' '}
          excluded.
        </AppText>
      ) : null}
      <View style={styles.actions}>
        <Button icon={Plus} label="Add draft entry" onPress={onAdd} variant="secondary" />
      </View>
      {draftEntries.map((entry, index) => (
        <View key={entry.clientId} style={[styles.previewEntry, { borderTopColor: colors.border }]}>
          <View style={styles.entryText}>
            <AppText numberOfLines={1} variant="caption">
              {entry.name}
            </AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {entryDescription(entry, incomeDate)}
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

function entryDescription(entry: TemplateApplicationDraftEntry, incomeDate: string) {
  if (entry.entryType === 'BILL') {
    const method = entry.paymentMethod === 'MANUAL' ? 'Manual Pay' : 'Autopay';
    const dueDate = applicationEntriesFromDraft(incomeDate, [entry])[0]?.dueDate;
    return dueDate ? `Bill | ${method} | Due ${dueDate}` : `Bill | ${method}`;
  }
  if (entry.entryType === 'SPENDING_BUCKET') return 'Spending Bucket';
  return 'Sinking Fund';
}

function newDraftClientId() {
  return `draft-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

const styles = StyleSheet.create({
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  center: { alignItems: 'center', flex: 1, justifyContent: 'center' },
  content: { gap: 20, paddingBottom: 36 },
  draftActions: { alignItems: 'center', flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  entryText: { flex: 1, gap: 3 },
  form: { gap: 16 },
  preview: { borderRadius: 8, borderWidth: 1, gap: 8, padding: 14 },
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
  },
  previewTitle: { flex: 1, gap: 3 },
  stepHeader: { gap: 4 },
});
