import { useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Modal, ScrollView, View } from 'react-native';

import type { Entry, Paycheck } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney, minorToInput, parseMoneyToMinor } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export function ActualBillSheet({
  entry,
  paycheck,
  onClose,
  onChanged,
}: {
  entry: Entry;
  paycheck: Paycheck;
  onClose: () => void;
  onChanged: () => Promise<unknown>;
}) {
  const api = useYuukaApi();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [amount, setAmount] = useState(minorToInput(entry.amountMinor));
  const [reviewedVersion, setReviewedVersion] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [savedMessage, setSavedMessage] = useState('');
  const [saving, setSaving] = useState(false);
  const inFlight = useRef(false);
  const context = useQuery({
    queryKey: ['recurring-bills', 'actual', entry.id, entry.version],
    queryFn: () => api.recurringBillActualContext(entry.id),
    enabled: entry.amountEstimated === true && !savedMessage,
    staleTime: 0,
  });
  const reviewed = reviewedVersion === context.data?.definitionVersion;
  async function save() {
    if (inFlight.current || !context.data || (context.data.definitionReviewRequired && !reviewed))
      return;
    inFlight.current = true;
    setSaving(true);
    setError('');
    try {
      const amountMinor = parseMoneyToMinor(amount);
      const result = await api.recordRecurringBillActual(entry.id, {
        definitionId: context.data.definitionId,
        definitionVersion: context.data.definitionVersion,
        occurrenceDate: context.data.occurrenceDate,
        occurrenceAmountVersion: context.data.occurrenceAmountVersion,
        entryVersion: entry.version,
        paycheckVersion: paycheck.version,
        amountMinor,
        confirmDefinitionChanges: reviewed,
      });
      const difference = result.unallocatedMinor - paycheck.unallocatedMinor;
      setSavedMessage(
        difference > 0
          ? `${formatMoney(difference, settings.currencyCode)} returned to Unallocated.`
          : difference < 0
            ? `${formatMoney(-difference, settings.currencyCode)} more set aside from this paycheck.`
            : 'Actual bill saved. The amount set aside stays the same.',
      );
      await onChanged();
    } catch (cause) {
      setError(
        displayError(
          cause,
          settings.currencyCode,
          'The actual bill could not be saved. Refresh before trying again.',
        ),
      );
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  }
  return (
    <Modal
      visible
      animationType="slide"
      onRequestClose={() => {
        if (!saving) onClose();
      }}
    >
      <ScrollView
        contentContainerStyle={{
          flexGrow: 1,
          gap: 16,
          padding: 24,
          backgroundColor: colors.background,
        }}
      >
        <AppText variant="title">Actual bill</AppText>
        <AppText>
          {entry.name} · {entry.sourceRecurringOccurrenceDate}
        </AppText>
        {savedMessage ? (
          <AppText>{savedMessage}</AppText>
        ) : (
          <>
            <AppText>
              Estimated amount: {formatMoney(entry.amountMinor, settings.currencyCode)}
            </AppText>
            <AppText>
              This updates only this bill in {paycheck.name}. Your estimate for future bills stays
              the same.
            </AppText>
            {context.isPending ? <YuukaLoadingState message="Loading bill details..." /> : null}
            {context.isError ? (
              <ErrorState
                message="Bill details could not be loaded."
                retry={() => context.refetch()}
              />
            ) : null}
            {context.data?.definitionReviewRequired ? (
              <View style={{ gap: 8 }}>
                <AppText>
                  The recurring bill has changed or been removed since this money was set aside.
                  Check the bill name and date above before continuing.
                </AppText>
                <Button
                  variant="secondary"
                  label={reviewed ? 'Bill reviewed' : 'This is the bill I want to update'}
                  onPress={() =>
                    setReviewedVersion(reviewed ? null : (context.data?.definitionVersion ?? null))
                  }
                />
              </View>
            ) : null}
            {context.data?.actualAmountMinor != null ? (
              <AppText>
                Previously entered actual bill:{' '}
                {formatMoney(context.data.actualAmountMinor, settings.currencyCode)}. Saving here
                replaces it for this date.
              </AppText>
            ) : null}
            <TextField
              label="Actual bill amount"
              keyboardType="decimal-pad"
              value={amount}
              onChangeText={setAmount}
            />
            {error ? (
              <>
                <AppText variant="error">{error}</AppText>
                <Button
                  variant="secondary"
                  label="Refresh bill details"
                  onPress={() => {
                    void Promise.all([onChanged(), context.refetch()]);
                    setReviewedVersion(null);
                  }}
                />
              </>
            ) : null}
            <Button
              label="Save actual bill"
              loading={saving}
              disabled={
                !context.data || Boolean(context.data.definitionReviewRequired && !reviewed)
              }
              onPress={() => {
                void save();
              }}
            />
          </>
        )}
        <Button
          label={savedMessage ? 'Done' : 'Cancel'}
          variant="secondary"
          disabled={saving}
          onPress={onClose}
        />
      </ScrollView>
    </Modal>
  );
}
