import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Link2, Plus, Unlink, X } from 'lucide-react-native';
import { useMemo, useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';

import type { Entry, Paycheck, RecurringBill, RecurringBillOccurrence } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import type { RecurringBillPayload } from '@/api/use-yuuka-api';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { RecurringBillEditor } from '@/features/recurring-bills/recurring-bill-editor';
import { statusLabel } from '@/features/recurring-bills/coverage';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

import {
  allocationChangeMessage,
  materialRecurringChanges,
  nearbyOccurrenceMonths,
  occurrenceForMonth,
  refreshedReconciliationSelection,
  timelineRange,
} from './reconciliation';

type Step =
  | 'closed'
  | 'definitions'
  | 'occurrence'
  | 'review-link'
  | 'create-definition'
  | 'create-occurrence'
  | 'review-create'
  | 'review-unlink';

export function RecurringBillSection({
  entry,
  onChanged,
  paycheck,
}: {
  entry: Entry;
  onChanged: (paycheck: Paycheck) => Promise<void>;
  paycheck: Paycheck;
}) {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const [step, setStep] = useState<Step>('closed');
  const linkedDefinition = useQuery({
    queryKey: ['recurring-bill', entry.sourceRecurringBillDefinitionId],
    queryFn: () => api.recurringBill(entry.sourceRecurringBillDefinitionId!),
    enabled: Boolean(entry.sourceRecurringBillDefinitionId),
    retry: false,
  });
  const linked = Boolean(
    entry.sourceRecurringBillDefinitionId && entry.sourceRecurringOccurrenceDate,
  );
  const linkedName = linkedDefinition.data?.name ?? entry.name;

  return (
    <View style={[styles.relationship, { borderColor: colors.border }]}>
      <AppText variant="label">Recurring Bill</AppText>
      <AppText>{linked ? linkedName : 'Not linked'}</AppText>
      {linked ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          {formatOccurrence(entry.sourceRecurringOccurrenceDate!)} occurrence
        </AppText>
      ) : null}
      {linkedDefinition.isError ? (
        <AppText style={{ color: colors.muted }} variant="caption">
          The linked definition is unavailable. The Bill snapshot is unchanged.
        </AppText>
      ) : null}
      <View style={styles.actions}>
        {linked ? (
          <>
            {linkedDefinition.data ? (
              <Button
                label="View recurring Bill"
                onPress={() => router.push(`/recurring-bills/${linkedDefinition.data!.id}`)}
                variant="secondary"
              />
            ) : null}
            <Button
              label="Change link"
              onPress={() => setStep('definitions')}
              variant="secondary"
            />
            <Button label="Remove link" onPress={() => setStep('review-unlink')} variant="ghost" />
          </>
        ) : (
          <>
            <Button
              icon={Link2}
              label="Link to recurring Bill"
              onPress={() => setStep('definitions')}
              variant="secondary"
            />
            <Button
              icon={Plus}
              label="Turn into recurring Bill"
              onPress={() => setStep('create-definition')}
              variant="secondary"
            />
          </>
        )}
      </View>
      <RecurringBillReconciliationSheet
        entry={entry}
        onChanged={onChanged}
        onClose={() => setStep('closed')}
        paycheck={paycheck}
        setStep={setStep}
        step={step}
      />
    </View>
  );
}

function RecurringBillReconciliationSheet({
  entry,
  onChanged,
  onClose,
  paycheck,
  setStep,
  step,
}: {
  entry: Entry;
  onChanged: (paycheck: Paycheck) => Promise<void>;
  onClose: () => void;
  paycheck: Paycheck;
  setStep: (step: Step) => void;
  step: Step;
}) {
  const api = useYuukaApi();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const inFlight = useRef(false);
  const [definition, setDefinition] = useState<RecurringBill | null>(null);
  const [occurrence, setOccurrence] = useState<RecurringBillOccurrence | null>(null);
  const [createdValues, setCreatedValues] = useState<RecurringBillPayload | null>(null);
  const [createdOccurrence, setCreatedOccurrence] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [recoveryMessage, setRecoveryMessage] = useState('');
  const anchor = entry.dueDate ?? paycheck.incomeDate;
  const range = useMemo(() => timelineRange(anchor), [anchor]);
  const definitions = useQuery({
    queryKey: ['recurring-bills', 'definitions', 'active'],
    queryFn: () => api.recurringBills('ACTIVE'),
    enabled: step === 'definitions' || step === 'occurrence',
  });
  const timeline = useQuery({
    queryKey: ['recurring-bills', 'reconciliation-options', anchor],
    queryFn: () => api.recurringBillTimeline(range.from, range.through),
    enabled: step === 'occurrence' && Boolean(definition),
  });

  const selectedOccurrence =
    occurrence ??
    (step === 'occurrence' && definition && entry.dueDate
      ? (timeline.data?.items.find(
          (item) =>
            item.definitionId === definition.id &&
            item.occurrenceDate.slice(0, 7) === entry.dueDate!.slice(0, 7),
        ) ?? null)
      : null);

  const mutation = useMutation({
    mutationFn: async (action: 'link' | 'create' | 'unlink') => {
      if (action === 'unlink')
        return api.unlinkRecurringBill(entry.id, entry.version, paycheck.version);
      if (action === 'create') {
        if (!createdValues || !createdOccurrence) throw new Error('Review an occurrence first.');
        return api.createRecurringBillFromEntry(entry.id, {
          ...createdValues,
          entryVersion: entry.version,
          paycheckVersion: paycheck.version,
          occurrenceDate: createdOccurrence,
        });
      }
      if (!selectedOccurrence) throw new Error('Choose a recurring Bill occurrence.');
      return api.linkRecurringBill(entry.id, {
        entryVersion: entry.version,
        paycheckVersion: paycheck.version,
        definitionId: selectedOccurrence.definitionId,
        definitionVersion: selectedOccurrence.definitionVersion,
        occurrenceDate: selectedOccurrence.occurrenceDate,
        confirmDuplicateOccurrence: selectedOccurrence.imports.some(
          (item) => item.entryId !== entry.id,
        ),
      });
    },
  });

  async function submit(action: 'link' | 'create' | 'unlink') {
    if (inFlight.current) return;
    inFlight.current = true;
    setError('');
    setRecoveryMessage('');
    try {
      const updated = await mutation.mutateAsync(action);
      queryClient.setQueryData(['paycheck', paycheck.id], updated);
      await onChanged(updated);
      onClose();
    } catch (mutationError) {
      const mutationMessage = displayError(
        mutationError,
        settings.currencyCode,
        'The recurring link was not saved.',
      );
      setError(mutationMessage);
      setRecoveryMessage('Refreshing current data before another attempt.');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['paycheck', paycheck.id] }),
        queryClient.invalidateQueries({ queryKey: ['recurring-bills'] }),
        queryClient.invalidateQueries({ queryKey: ['recurring-bill'] }),
      ]);
      try {
        const refreshedPaycheck = await api.paycheck(paycheck.id);
        queryClient.setQueryData(['paycheck', paycheck.id], refreshedPaycheck);
        if (definition && selectedOccurrence) {
          const [refreshedDefinitions, refreshedTimeline] = await Promise.all([
            api.recurringBills('ACTIVE'),
            api.recurringBillTimeline(range.from, range.through),
          ]);
          queryClient.setQueryData(
            ['recurring-bills', 'definitions', 'active'],
            refreshedDefinitions,
          );
          queryClient.setQueryData(
            ['recurring-bills', 'reconciliation-options', anchor],
            refreshedTimeline,
          );
          const refreshedSelection = refreshedReconciliationSelection(
            refreshedDefinitions.items,
            refreshedTimeline.items,
            selectedOccurrence.definitionId,
            selectedOccurrence.occurrenceDate,
          );
          setDefinition(refreshedSelection.definition);
          setOccurrence(refreshedSelection.occurrence);
          setRecoveryMessage(refreshedSelection.message);
          if (refreshedSelection.kind === 'definition-unavailable') {
            setStep('definitions');
            return;
          }
          if (refreshedSelection.kind === 'occurrence-unavailable') {
            setStep('occurrence');
            return;
          }
        }
      } catch {
        setRecoveryMessage(
          'Current data could not be refreshed. Retry when the connection is available.',
        );
      }
    } finally {
      inFlight.current = false;
    }
  }

  function close() {
    if (mutation.isPending) return;
    setDefinition(null);
    setOccurrence(null);
    setCreatedValues(null);
    setCreatedOccurrence(null);
    setError('');
    setRecoveryMessage('');
    onClose();
  }

  if (step === 'closed') return null;
  if (step === 'create-definition') {
    return (
      <Modal animationType="slide" onRequestClose={close} visible>
        <SheetHeader onClose={close} title="Turn into recurring Bill" />
        <RecurringBillEditor
          initialValues={{
            accountName: entry.accountName,
            dueDay: entry.dueDate ? Number(entry.dueDate.slice(-2)) : undefined,
            name: entry.name,
            notes: entry.notes,
            payee: entry.payee,
            paymentMethod: entry.paymentMethod ?? 'AUTOPAY',
            typicalAmountMinor: entry.amountMinor,
          }}
          onSubmit={async (payload) => {
            setCreatedValues(payload);
            setCreatedOccurrence(
              entry.dueDate ? occurrenceForMonth(entry.dueDate, payload.dueDay) : null,
            );
            setStep('create-occurrence');
          }}
          submitLabel="Review occurrence"
        />
      </Modal>
    );
  }

  return (
    <Modal animationType="slide" onRequestClose={close} visible>
      <View style={[styles.screen, { backgroundColor: colors.background }]}>
        <SheetHeader onClose={close} title={titleForStep(step)} />
        <ScrollView contentContainerStyle={styles.content}>
          {step === 'definitions' ? (
            <DefinitionPicker
              error={definitions.error}
              loading={definitions.isPending}
              onRetry={() => definitions.refetch()}
              onSelect={(selected) => {
                setDefinition(selected);
                setOccurrence(null);
                setStep('occurrence');
              }}
              values={definitions.data?.items ?? []}
            />
          ) : null}
          {step === 'occurrence' ? (
            <OccurrencePicker
              current={selectedOccurrence}
              entry={entry}
              error={timeline.error}
              loading={timeline.isPending}
              onContinue={() => {
                if (selectedOccurrence) setOccurrence(selectedOccurrence);
                setStep('review-link');
              }}
              onRetry={() => timeline.refetch()}
              onSelect={setOccurrence}
              values={(timeline.data?.items ?? []).filter(
                (item) => item.definitionId === definition?.id,
              )}
            />
          ) : null}
          {step === 'review-link' && definition && selectedOccurrence ? (
            <ReviewChanges
              definition={selectedOccurrence}
              entry={entry}
              occurrence={selectedOccurrence.occurrenceDate}
              duplicateImports={selectedOccurrence.imports.filter(
                (item) => item.entryId !== entry.id,
              )}
              onConfirm={() => submit('link')}
              paycheck={paycheck}
              pending={mutation.isPending}
            />
          ) : null}
          {step === 'create-occurrence' && createdValues ? (
            <CreateOccurrencePicker
              anchor={anchor}
              dueDay={createdValues.dueDay}
              explicitRequired={!entry.dueDate}
              onContinue={() => setStep('review-create')}
              onSelect={setCreatedOccurrence}
              selected={createdOccurrence}
            />
          ) : null}
          {step === 'review-create' && createdValues && createdOccurrence ? (
            <ReviewChanges
              definition={{
                accountName: createdValues.accountName ?? null,
                name: createdValues.name,
                notes: createdValues.notes ?? null,
                payee: createdValues.payee ?? null,
                paymentMethod: createdValues.paymentMethod ?? 'AUTOPAY',
                typicalAmountMinor: createdValues.typicalAmountMinor,
              }}
              entry={entry}
              occurrence={createdOccurrence}
              onConfirm={() => submit('create')}
              paycheck={paycheck}
              pending={mutation.isPending}
            />
          ) : null}
          {step === 'review-unlink' ? (
            <View style={styles.section}>
              <AppText>
                Remove the recurring relationship from this existing Bill? Its current name, amount,
                due date, payment method, account, payee, notes, status, and history will stay
                unchanged.
              </AppText>
              <Button
                icon={Unlink}
                label="Confirm remove link"
                loading={mutation.isPending}
                onPress={() => submit('unlink')}
                variant="danger"
              />
            </View>
          ) : null}
          {error ? (
            <View style={[styles.error, { borderColor: colors.danger }]}>
              <AppText style={{ color: colors.danger }} variant="error">
                {error}
              </AppText>
              {recoveryMessage ? (
                <AppText style={{ color: colors.muted }} variant="caption">
                  {recoveryMessage}
                </AppText>
              ) : null}
            </View>
          ) : null}
        </ScrollView>
      </View>
    </Modal>
  );
}

function DefinitionPicker({
  error,
  loading,
  onRetry,
  onSelect,
  values,
}: {
  error: Error | null;
  loading: boolean;
  onRetry: () => unknown;
  onSelect: (value: RecurringBill) => void;
  values: RecurringBill[];
}) {
  if (loading) return <YuukaLoadingState message="Loading recurring Bills..." />;
  if (error) return <ErrorState message={error.message} retry={() => void onRetry()} />;
  if (!values.length) return <AppText>No Active recurring Bills are available.</AppText>;
  return (
    <View style={styles.section}>
      <AppText style={styles.explanation}>
        Choose the Active definition whose monthly occurrence this existing Bill represents.
      </AppText>
      {values.map((item) => (
        <Button
          key={item.id}
          label={`Choose ${item.name}`}
          onPress={() => onSelect(item)}
          variant="secondary"
        />
      ))}
    </View>
  );
}

function OccurrencePicker({
  current,
  entry,
  error,
  loading,
  onContinue,
  onRetry,
  onSelect,
  values,
}: {
  current: RecurringBillOccurrence | null;
  entry: Entry;
  error: Error | null;
  loading: boolean;
  onContinue: () => void;
  onRetry: () => unknown;
  onSelect: (value: RecurringBillOccurrence) => void;
  values: RecurringBillOccurrence[];
}) {
  if (loading) return <YuukaLoadingState message="Loading occurrences..." />;
  if (error) return <ErrorState message={error.message} retry={() => void onRetry()} />;
  return (
    <View style={styles.section}>
      <AppText>
        {entry.dueDate
          ? `The occurrence in ${formatMonth(entry.dueDate)} is suggested from the current due date. Review or choose another nearby month.`
          : 'This Bill has no due date. Choose an occurrence month explicitly.'}
      </AppText>
      {values.map((item) => (
        <Button
          key={`${item.definitionId}:${item.occurrenceDate}`}
          label={`${current?.occurrenceDate === item.occurrenceDate ? 'Selected' : 'Choose'} ${formatOccurrence(item.occurrenceDate)} occurrence`}
          onPress={() => onSelect(item)}
          variant="secondary"
        />
      ))}
      <Button disabled={!current} label="Review changes" onPress={onContinue} />
    </View>
  );
}

function CreateOccurrencePicker({
  anchor,
  dueDay,
  explicitRequired,
  onContinue,
  onSelect,
  selected,
}: {
  anchor: string;
  dueDay: number;
  explicitRequired: boolean;
  onContinue: () => void;
  onSelect: (value: string) => void;
  selected: string | null;
}) {
  return (
    <View style={styles.section}>
      <AppText>
        {explicitRequired
          ? 'Choose the occurrence month this Bill represents. The paycheck income month is shown only as a starting point.'
          : 'Review the suggested occurrence or choose another nearby month.'}
      </AppText>
      {nearbyOccurrenceMonths(anchor).map((month) => {
        const value = occurrenceForMonth(month, dueDay);
        return (
          <Button
            key={month}
            label={`${selected === value ? 'Selected' : 'Choose'} ${formatOccurrence(value)} occurrence`}
            onPress={() => onSelect(value)}
            variant="secondary"
          />
        );
      })}
      <Button disabled={!selected} label="Review changes" onPress={onContinue} />
    </View>
  );
}

function ReviewChanges({
  definition,
  duplicateImports = [],
  entry,
  occurrence,
  onConfirm,
  paycheck,
  pending,
}: {
  definition: Parameters<typeof materialRecurringChanges>[1];
  duplicateImports?: RecurringBillOccurrence['imports'];
  entry: Entry;
  occurrence: string;
  onConfirm: () => void;
  paycheck: Paycheck;
  pending: boolean;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const money = (value: number) => formatMoney(value, settings.currencyCode);
  const changes = materialRecurringChanges(entry, definition, occurrence, money);
  return (
    <View style={styles.section}>
      <AppText variant="title">Link to {definition.name}</AppText>
      <AppText style={{ color: colors.muted }} variant="caption">
        This updates Bill {entry.name} in {paycheck.name} without creating a second entry. Its
        status, position, and history stay attached to the same Bill.
      </AppText>
      {changes.length ? (
        changes.map((change) => (
          <View key={change.field} style={[styles.change, { borderColor: colors.border }]}>
            <AppText variant="label">{change.field}</AppText>
            <AppText>
              {change.before} → {change.after}
            </AppText>
          </View>
        ))
      ) : (
        <AppText>No snapshot fields change.</AppText>
      )}
      <View style={[styles.change, { borderColor: colors.border }]}>
        <AppText variant="label">Allocation</AppText>
        <AppText>
          {allocationChangeMessage(entry.amountMinor, definition.typicalAmountMinor, money)}
        </AppText>
      </View>
      {duplicateImports.length ? (
        <View style={[styles.warning, { borderColor: colors.danger }]}>
          <AppText variant="label">This occurrence is already assigned</AppText>
          {duplicateImports.map((item) => (
            <AppText key={item.entryId}>
              {item.paycheckName} · {statusLabel(item.status)}
            </AppText>
          ))}
          <AppText>Link this Bill as another assignment?</AppText>
        </View>
      ) : null}
      <Button
        label={duplicateImports.length ? 'Confirm another assignment' : 'Confirm recurring link'}
        loading={pending}
        onPress={onConfirm}
      />
    </View>
  );
}

function SheetHeader({ onClose, title }: { onClose: () => void; title: string }) {
  const { colors } = useAppTheme();
  return (
    <View
      style={[styles.header, { backgroundColor: colors.background, borderColor: colors.border }]}
    >
      <AppText variant="title">{title}</AppText>
      <Pressable
        accessibilityLabel="Close recurring Bill workflow"
        onPress={onClose}
        style={styles.close}
      >
        <X color={colors.text} size={23} />
      </Pressable>
    </View>
  );
}

function titleForStep(step: Step) {
  if (step === 'definitions') return 'Choose recurring Bill';
  if (step === 'occurrence' || step === 'create-occurrence') return 'Choose occurrence';
  if (step === 'review-unlink') return 'Remove recurring link';
  return 'Review recurring link';
}

function formatOccurrence(value: string) {
  return new Intl.DateTimeFormat('en-US', {
    day: 'numeric',
    month: 'long',
    timeZone: 'UTC',
  }).format(new Date(`${value}T00:00:00Z`));
}

function formatMonth(value: string) {
  return new Intl.DateTimeFormat('en-US', {
    month: 'long',
    timeZone: 'UTC',
    year: 'numeric',
  }).format(new Date(`${value}T00:00:00Z`));
}

const styles = StyleSheet.create({
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  change: { borderRadius: 8, borderWidth: 1, gap: 4, padding: 12 },
  close: { alignItems: 'center', justifyContent: 'center', minHeight: 48, minWidth: 48 },
  content: { gap: 16, padding: 16, paddingBottom: 40 },
  error: { borderRadius: 8, borderWidth: 1, gap: 4, padding: 12 },
  explanation: { marginBottom: 4 },
  header: {
    alignItems: 'center',
    borderBottomWidth: 1,
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 12,
  },
  relationship: { borderRadius: 8, borderWidth: 1, gap: 8, padding: 12 },
  screen: { flex: 1 },
  section: { gap: 12 },
  warning: { borderRadius: 8, borderWidth: 1, gap: 6, padding: 12 },
});
