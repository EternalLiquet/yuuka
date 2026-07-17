import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { Pencil, Power, Trash2 } from 'lucide-react-native';
import { StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { ErrorState, YuukaLoadingState } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function RecurringBillDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const query = useQuery({
    queryKey: ['recurring-bill', id],
    queryFn: () => api.recurringBill(id),
  });
  const invalidate = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['recurring-bill', id] }),
      queryClient.invalidateQueries({ queryKey: ['recurring-bills'] }),
    ]);
  };
  const activeMutation = useMutation({
    mutationFn: async () => {
      if (!query.data) throw new Error('Refresh the recurring Bill first.');
      return query.data.active
        ? api.deactivateRecurringBill(id, query.data.version)
        : api.activateRecurringBill(id, query.data.version);
    },
    onSuccess: invalidate,
  });
  const deleteMutation = useMutation({
    mutationFn: async () => {
      if (!query.data) throw new Error('Refresh the recurring Bill first.');
      await api.deleteRecurringBill(id, query.data.version);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['recurring-bills'] });
      router.replace('/recurring-bills/manage');
    },
  });

  if (query.isPending) return <YuukaLoadingState message="Loading recurring Bill..." />;
  if (query.isError || !query.data) {
    return (
      <ErrorState
        message={displayError(
          query.error,
          settings.currencyCode,
          'The recurring Bill could not load.',
        )}
        retry={() => query.refetch()}
      />
    );
  }
  const definition = query.data;
  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: definition.name,
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <ScrollScreen contentContainerStyle={styles.content}>
        <View style={styles.heading}>
          <AppText variant="title">{definition.name}</AppText>
          <AppText variant="money">
            {formatMoney(definition.typicalAmountMinor, settings.currencyCode)}
          </AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            Typical amount
          </AppText>
        </View>
        <Detail
          label="Due-day rule"
          value={`Day ${definition.dueDay}; short months use their final day`}
        />
        <Detail
          label="Payment method"
          value={definition.paymentMethod === 'MANUAL' ? 'Manual' : 'Autopay'}
        />
        <Detail label="Account" value={definition.accountName ?? 'Not set'} />
        <Detail label="Payee" value={definition.payee ?? 'Not set'} />
        <Detail label="Notes" value={definition.notes ?? 'Not set'} />
        <Detail label="State" value={definition.active ? 'Active' : 'Inactive'} />
        <Button
          icon={Pencil}
          label="Edit recurring Bill"
          onPress={() => router.push(`/recurring-bills/${id}/edit`)}
        />
        <Button
          icon={Power}
          label={definition.active ? 'Deactivate recurring Bill' : 'Activate recurring Bill'}
          loading={activeMutation.isPending}
          onPress={() => activeMutation.mutate()}
          variant="secondary"
        />
        <Button
          icon={Trash2}
          label="Delete recurring Bill"
          loading={deleteMutation.isPending}
          onPress={() => deleteMutation.mutate()}
          variant="danger"
        />
        {activeMutation.error || deleteMutation.error ? (
          <AppText style={{ color: colors.danger }} variant="error">
            {displayError(activeMutation.error ?? deleteMutation.error, settings.currencyCode)}
          </AppText>
        ) : null}
      </ScrollScreen>
    </>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={[styles.detail, { borderBottomColor: colors.border }]}>
      <AppText style={{ color: colors.muted }} variant="caption">
        {label}
      </AppText>
      <AppText>{value}</AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  content: { gap: 14, paddingBottom: 36 },
  detail: { borderBottomWidth: 1, gap: 5, paddingBottom: 12 },
  heading: { alignItems: 'center', gap: 5, paddingVertical: 12 },
});
