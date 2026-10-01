import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { View } from 'react-native';

import type { RecurringBillOccurrence } from '@/api/contracts';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney, parseMoneyToMinor } from '@/domain/money';
import type { TemplateApplicationDraftEntry } from '@/features/templates/application-draft';
import { useSettings } from '@/settings/settings-provider';

import { timelineRange } from './reconciliation';

export function DuplicateRecurringBill({
  entry,
  incomeDate,
  duplicateInDraft,
  onChange,
}: {
  entry: TemplateApplicationDraftEntry;
  incomeDate: string;
  duplicateInDraft: (date: string) => boolean;
  onChange: (entry: TemplateApplicationDraftEntry) => void;
}) {
  const api = useYuukaApi();
  const { settings } = useSettings();
  const [manualAmount, setManualAmount] = useState('');
  const [error, setError] = useState('');
  const validDate = /^\d{4}-\d{2}-\d{2}$/.test(incomeDate) && !Number.isNaN(Date.parse(incomeDate));
  const range = timelineRange(validDate ? incomeDate : '2000-01-01');
  const query = useQuery({
    queryKey: ['recurring-bills', 'duplicate-options', incomeDate],
    queryFn: () => api.recurringBillTimeline(range.from, range.through),
    enabled: validDate,
  });
  const options =
    query.data?.items.filter(
      (item) => item.definitionId === entry.sourceRecurringBillDefinitionId,
    ) ?? [];
  const selected =
    entry.reviewedIncomeDate === incomeDate
      ? options.find((item) => item.occurrenceDate === entry.sourceRecurringOccurrenceDate)
      : undefined;
  const duplicate =
    selected && (selected.importCount > 0 || duplicateInDraft(selected.occurrenceDate));
  function choose(item: RecurringBillOccurrence) {
    setError('');
    onChange({
      ...entry,
      sourceRecurringOccurrenceDate: item.occurrenceDate,
      recurringDefinitionVersion: item.definitionVersion,
      occurrenceAmountVersion: item.occurrenceAmountVersion,
      amountMinor: item.amountMinor ?? item.planningAmountMinor ?? 0,
      amountEstimated: item.amountMode === 'VARIABLE' && item.amountMinor == null,
      reviewedIncomeDate:
        item.amountMinor != null || item.planningAmountMinor != null ? incomeDate : undefined,
      confirmDuplicateOccurrence: false,
      duplicateConfirmationRequired: item.importCount > 0 || duplicateInDraft(item.occurrenceDate),
      defaultDueOffsetDays: null,
    });
  }
  function saveManual() {
    try {
      const amountMinor = parseMoneyToMinor(manualAmount);
      onChange({ ...entry, amountMinor, amountEstimated: true, reviewedIncomeDate: incomeDate });
      setError('');
    } catch {
      setError('Enter a valid estimated amount.');
    }
  }
  const pending = options.find(
    (item) => item.occurrenceDate === entry.sourceRecurringOccurrenceDate,
  );
  if (!validDate) return <AppText>Enter a valid income date first.</AppText>;
  if (query.isPending) return <YuukaLoadingState message="Loading bill dates..." />;
  if (query.isError)
    return <ErrorState message="Bill dates could not be loaded." retry={() => query.refetch()} />;
  return (
    <View style={{ gap: 8 }}>
      <AppText>Choose which bill belongs in this paycheck.</AppText>
      <Button
        variant="ghost"
        label="Refresh bill dates"
        loading={query.isFetching}
        onPress={() => {
          onChange({ ...entry, reviewedIncomeDate: undefined, confirmDuplicateOccurrence: false });
          void query.refetch();
        }}
      />
      {!options.length ? (
        <AppText>
          This recurring bill is no longer available. Remove it from this draft or reactivate it
          before continuing.
        </AppText>
      ) : null}
      {options.map((item) => {
        const suggested =
          entry.sourceIncomeDate?.slice(0, 7) !== incomeDate.slice(0, 7) &&
          entry.sourceBillDate?.slice(0, 7) === entry.sourceIncomeDate?.slice(0, 7) &&
          item.occurrenceDate.slice(0, 7) === incomeDate.slice(0, 7);
        return (
          <Button
            key={item.occurrenceDate}
            variant="secondary"
            disabled={query.isFetching}
            label={`${selected?.occurrenceDate === item.occurrenceDate ? 'Selected' : 'Choose'} ${item.occurrenceDate}${suggested ? ' (suggested)' : ''}`}
            onPress={() => choose(item)}
          />
        );
      })}
      {pending && pending.amountMinor == null && pending.planningAmountMinor == null ? (
        <>
          <TextField
            label={`Estimated amount for ${entry.name}`}
            keyboardType="decimal-pad"
            value={manualAmount}
            onChangeText={setManualAmount}
          />
          <Button label="Use this estimate" onPress={saveManual} />
        </>
      ) : null}
      {selected ? (
        <AppText>
          {entry.amountEstimated ? 'Estimated amount' : 'Bill amount'}:{' '}
          {formatMoney(entry.amountMinor, settings.currencyCode)}
        </AppText>
      ) : null}
      {duplicate ? (
        <>
          <AppText>
            This bill is already in another paycheck or elsewhere in this draft. Only add another
            copy if you mean to set aside money for it again.
          </AppText>
          <Button
            variant="secondary"
            disabled={query.isFetching}
            label={
              entry.confirmDuplicateOccurrence
                ? 'Another copy confirmed'
                : 'Add another copy of this bill'
            }
            onPress={() =>
              onChange({ ...entry, confirmDuplicateOccurrence: !entry.confirmDuplicateOccurrence })
            }
          />
        </>
      ) : null}
      {error ? <AppText variant="error">{error}</AppText> : null}
    </View>
  );
}
