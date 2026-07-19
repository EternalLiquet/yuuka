import { useInfiniteQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Plus } from 'lucide-react-native';
import { useMemo, useState } from 'react';
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

const PAGE_SIZE = 50;

export default function ExpenseLedgersScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [state, setState] = useState<ExpenseLedgerState>('OPEN');
  const query = useInfiniteQuery({
    queryKey: ['expense-ledgers', state],
    queryFn: ({ pageParam }) => api.expenseLedgers(state, pageParam, PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.page + 1 : undefined),
  });
  const ledgers = useMemo(() => {
    const byId = new Map<string, ExpenseLedger>();
    query.data?.pages.forEach((page) =>
      page.items.forEach((ledger) => byId.set(ledger.id, ledger)),
    );
    return [...byId.values()];
  }, [query.data?.pages]);
  const totalItems = query.data?.pages[0]?.totalItems ?? 0;
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 750);

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading Expense Lists..." />
      </Screen>
    );
  }

  return (
    <Screen>
      <FlatList
        contentContainerStyle={styles.content}
        data={ledgers}
        keyExtractor={(item) => item.id}
        ListEmptyComponent={
          query.isError && !query.data ? (
            <ErrorState
              message={displayError(
                query.error,
                settings.currencyCode,
                'Expense Lists could not be loaded.',
              )}
              retry={() => query.refetch()}
            />
          ) : (
            <EmptyState
              mascot="idle"
              message={
                state === 'OPEN'
                  ? 'Capture expenses first, then settle the total into a Bill or Payback.'
                  : 'No Expense Lists in this state.'
              }
              title="No Expense Lists"
            />
          )
        }
        ListFooterComponent={
          query.hasNextPage || query.isFetchNextPageError ? (
            <View style={styles.footer}>
              {query.isFetchNextPageError ? (
                <AppText style={{ color: colors.danger }} variant="error">
                  Older Expense Lists could not be loaded.
                </AppText>
              ) : null}
              <Button
                label={
                  query.isFetchNextPageError
                    ? 'Retry older expense lists'
                    : 'Load older expense lists'
                }
                loading={query.isFetchingNextPage}
                onPress={() => void query.fetchNextPage()}
                variant="secondary"
              />
            </View>
          ) : null
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Expense Lists</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  Showing {ledgers.length} of {totalItems} {state.toLowerCase()} expense lists
                </AppText>
              </View>
              <Button
                icon={Plus}
                label="New Expense List"
                onPress={() => router.push('/expense-ledgers/new')}
              />
            </View>
            <SegmentedControl
              label="Expense List state"
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
            accessibilityLabel="Refresh Expense Lists"
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
  footer: { gap: 8, paddingTop: 4 },
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
