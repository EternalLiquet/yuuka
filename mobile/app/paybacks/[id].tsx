import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { ArrowLeft, Pencil } from 'lucide-react-native';
import { useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { Payback } from '@/api/contracts';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { IconButton } from '@/components/icon-button';
import { ProgressBar } from '@/components/progress-bar';
import { Screen } from '@/components/screen';
import { ErrorState } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { PaybackEditor } from '@/features/paybacks/payback-editor';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function PaybackDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [editing, setEditing] = useState(false);
  const paybackQuery = useQuery({
    queryKey: ['payback', id],
    queryFn: () => api.payback(id),
    enabled: Boolean(id),
  });
  const repaymentQuery = useQuery({
    queryKey: ['payback', id, 'repayments'],
    queryFn: () => api.paybackRepayments(id),
    enabled: Boolean(id),
  });
  const updateMutation = useMutation({
    mutationFn: (values: {
      borrowedDate: string;
      name: string;
      notes: string | null;
      openingRemainingAmountMinor: number;
      originalAmountMinor: number;
      source: string | null;
    }) => {
      if (!paybackQuery.data) throw new Error('Refresh the Payback before editing.');
      return api.updatePayback(id, { ...values, version: paybackQuery.data.version });
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['paybacks'] }),
        queryClient.invalidateQueries({ queryKey: ['payback', id] }),
      ]);
      setEditing(false);
    },
  });

  if (editing && paybackQuery.data) {
    return (
      <PaybackEditor
        onClose={() => setEditing(false)}
        onSubmit={(values) => updateMutation.mutateAsync(values).then(() => undefined)}
        payback={paybackQuery.data}
      />
    );
  }

  return (
    <Screen>
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <IconButton icon={ArrowLeft} label="Back" onPress={() => router.back()} />
          {paybackQuery.data ? (
            <Button
              icon={Pencil}
              label="Edit Payback"
              onPress={() => setEditing(true)}
              variant="secondary"
            />
          ) : null}
        </View>
        {paybackQuery.isPending ? (
          <ActivityIndicator color={colors.accent} size="large" style={styles.loader} />
        ) : paybackQuery.isError || !paybackQuery.data ? (
          <ErrorState
            message={displayError(
              paybackQuery.error,
              settings.currencyCode,
              'Check the API connection and try again.',
            )}
            retry={() => paybackQuery.refetch()}
          />
        ) : (
          <PaybackDetail payback={paybackQuery.data} />
        )}
        {paybackQuery.data ? (
          <View style={styles.section}>
            <AppText variant="label">Repayment history</AppText>
            {repaymentQuery.isPending ? (
              <ActivityIndicator color={colors.accent} />
            ) : repaymentQuery.isError ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {displayError(
                  repaymentQuery.error,
                  settings.currencyCode,
                  'Repayment history could not be loaded.',
                )}
              </AppText>
            ) : repaymentQuery.data?.items.length ? (
              repaymentQuery.data.items.map((repayment) => (
                <View
                  key={repayment.id}
                  style={[
                    styles.historyRow,
                    { backgroundColor: colors.surface, borderColor: colors.border },
                  ]}
                >
                  <View style={styles.historyHeader}>
                    <AppText variant="label">{repayment.entryName}</AppText>
                    <AppText variant="label">
                      {formatMoney(repayment.amountMinor, settings.currencyCode)}
                    </AppText>
                  </View>
                  <AppText style={{ color: colors.muted }} variant="caption">
                    {repayment.paycheckName} | {formatDate(repayment.paycheckIncomeDate)}
                  </AppText>
                  <AppText style={{ color: colors.muted }} variant="caption">
                    {repayment.reversedAt
                      ? `Reversed ${formatDateTime(repayment.reversedAt)}`
                      : `Applied ${formatDateTime(repayment.appliedAt)}`}
                  </AppText>
                </View>
              ))
            ) : (
              <AppText style={{ color: colors.muted }} variant="caption">
                No posted repayments yet.
              </AppText>
            )}
          </View>
        ) : null}
      </ScrollView>
    </Screen>
  );
}

function PaybackDetail({ payback }: { payback: Payback }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const progress = Math.round(payback.progressPercent);
  return (
    <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.titleRow}>
        <View style={styles.titleBlock}>
          <AppText variant="title">{payback.name}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            Started {formatDate(payback.borrowedDate)}
            {payback.source ? ` | ${payback.source}` : ''}
          </AppText>
        </View>
        <AppText
          style={{ color: payback.state === 'PAID_OFF' ? colors.posted : colors.muted }}
          variant="label"
        >
          {payback.state === 'PAID_OFF' ? 'Paid Off' : 'Active'}
        </AppText>
      </View>
      <Metric
        label="Remaining"
        value={formatMoney(payback.remainingMinor, settings.currencyCode)}
      />
      <View style={styles.metrics}>
        <Metric
          label="Original amount"
          value={formatMoney(payback.originalAmountMinor, settings.currencyCode)}
        />
        <Metric
          label="Tracked from"
          value={formatMoney(payback.openingRemainingAmountMinor, settings.currencyCode)}
        />
      </View>
      <Metric
        label="Repaid in Yuuka"
        value={formatMoney(payback.repaidMinor, settings.currencyCode)}
      />
      <ProgressBar
        accessibilityLabel={`${progress} percent repaid since tracking began, ${formatMoney(payback.remainingMinor, settings.currencyCode)} remaining`}
        tone="posted"
        value={payback.progressPercent}
      />
      {payback.notes ? (
        <AppText style={{ color: colors.muted }} variant="body">
          {payback.notes}
        </AppText>
      ) : null}
    </View>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.metric}>
      <AppText style={{ color: colors.muted }} variant="caption">
        {label}
      </AppText>
      <AppText variant="label">{value}</AppText>
    </View>
  );
}

function formatDate(value: string) {
  return new Date(`${value}T00:00:00`).toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  });
}

const styles = StyleSheet.create({
  card: { borderRadius: 8, borderWidth: 1, gap: 15, padding: 16 },
  content: { gap: 16, padding: 16, paddingBottom: 32 },
  header: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
  historyHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
  },
  historyRow: { borderRadius: 8, borderWidth: 1, gap: 6, padding: 13 },
  loader: { marginTop: 80 },
  metric: { flex: 1, gap: 3 },
  metrics: { flexDirection: 'row', gap: 12 },
  section: { gap: 10 },
  titleBlock: { flex: 1, gap: 4 },
  titleRow: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
  },
});
