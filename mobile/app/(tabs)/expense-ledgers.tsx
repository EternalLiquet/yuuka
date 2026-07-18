import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Plus } from 'lucide-react-native';
import { useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';

import { ExpenseLedger, ExpenseLedgerState } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { SegmentedControl } from '@/components/segmented-control';
import {
  EmptyState,
  ErrorState,
  StaleBanner,
  YuukaLoadingState,
  YuukaRefreshIndicator,
} from '@/components/states';
import { formatMoney } from '@/domain/money';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const STATE_OPTIONS = [
  { label: 'Open', value: 'OPEN' },
  { label: 'Finalized', value: 'FINALIZED' },
  { label: 'Settled', value: 'SETTLED' },
] as const;

export default function ExpenseLedgersScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [state, setState] = useState<ExpenseLedgerState>('OPEN');
  const query = useQuery({
    queryKey: ['expense-ledgers', state],
    queryFn: () => api.expenseLedgers(state),
  });
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 750);

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading Expense Ledgers..." />
      </Screen>
    );
  }

  return (
    <Screen>
      <FlatList
        contentContainerStyle={styles.content}
        data={query.data?.items ?? []}
        keyExtractor={(item) => item.id}
        ListEmptyComponent={
          query.isError && !query.data ? (
            <ErrorState
              message={displayError(
                query.error,
                settings.currencyCode,
                'Expense Ledgers could not be loaded.',
              )}
              retry={() => query.refetch()}
            />
          ) : (
            <EmptyState
              mascot="idle"
              message={
                state === 'OPEN'
                  ? 'Capture expenses first, then settle the total into a Bill or Payback.'
                  : 'No Expense Ledgers in this state.'
              }
              title="No Expense Ledgers"
            />
          )
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Expense Ledgers</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {query.data?.totalItems ?? 0} {state.toLowerCase()}
                </AppText>
              </View>
              <Button
                icon={Plus}
                label="New Ledger"
                onPress={() => router.push('/expense-ledgers/new')}
              />
            </View>
            <SegmentedControl
              label="Expense Ledger state"
              onChange={setState}
              options={STATE_OPTIONS}
              value={state}
            />
            <YuukaRefreshIndicator visible={query.isFetching && Boolean(query.data)} />
            {query.isError && query.data ? <StaleBanner /> : null}
          </View>
        }
        refreshControl={
          <RefreshControl
            accessibilityLabel="Refresh Expense Ledgers"
            colors={['transparent']}
            onRefresh={() => void query.refetch()}
            progressBackgroundColor="transparent"
            refreshing={query.isRefetching}
            tintColor="transparent"
          />
        }
        renderItem={({ item }) => (
          <LedgerRow
            currencyCode={settings.currencyCode}
            ledger={item}
            onPress={() => router.push(`/expense-ledgers/${item.id}`)}
          />
        )}
      />
    </Screen>
  );
}

function LedgerRow({
  currencyCode,
  ledger,
  onPress,
}: {
  currencyCode: string;
  ledger: ExpenseLedger;
  onPress: () => void;
}) {
  const { colors } = useAppTheme();
  return (
    <Pressable
      accessibilityLabel={`${ledger.name}, ${formatMoney(ledger.totalMinor, currencyCode)}`}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.ledgerRow,
        { backgroundColor: colors.surface, borderColor: colors.border },
        pressed && styles.pressed,
      ]}
    >
      <View style={styles.ledgerText}>
        <AppText variant="label">{ledger.name}</AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          {ledger.itemCount} items
          {ledger.latestExpenseDate ? ` | ${ledger.latestExpenseDate}` : ''}
        </AppText>
      </View>
      <AppText variant="label">{formatMoney(ledger.totalMinor, currencyCode)}</AppText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  header: { gap: 13, marginBottom: 2 },
  ledgerRow: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
    minHeight: 62,
    padding: 12,
  },
  ledgerText: { flex: 1, gap: 3 },
  pressed: { opacity: 0.72 },
  titleBlock: { gap: 3 },
  titleRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
});
