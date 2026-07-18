import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { ArrowDown, ArrowUp, Plus } from 'lucide-react-native';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';

import { SinkingFund } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { IconButton } from '@/components/icon-button';
import { Screen } from '@/components/screen';
import {
  EmptyState,
  ErrorState,
  StaleBanner,
  YuukaLoadingState,
  YuukaRefreshIndicator,
} from '@/components/states';
import { formatMoney } from '@/domain/money';
import { SinkingFundCard } from '@/features/sinking-funds/sinking-fund-card';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

type Row =
  | { id: string; kind: 'section'; title: string }
  | { kind: 'sinking-fund'; sinkingFund: SinkingFund };

export default function SinkingFundsScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const query = useQuery({ queryKey: ['sinking-funds'], queryFn: () => api.sinkingFunds(true) });
  const active = query.data?.items.filter((fund) => fund.state === 'ACTIVE') ?? [];
  const archived = query.data?.items.filter((fund) => fund.state === 'ARCHIVED') ?? [];
  const reorderMutation = useMutation({
    mutationFn: (sinkingFundIds: string[]) => api.reorderSinkingFunds(sinkingFundIds),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['sinking-funds'] });
    },
  });
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 1000);
  const rows: Row[] = [
    ...(active.length
      ? [{ kind: 'section' as const, id: 'active', title: 'Active Sinking Funds' }]
      : []),
    ...active.map((sinkingFund) => ({ kind: 'sinking-fund' as const, sinkingFund })),
    ...(archived.length ? [{ kind: 'section' as const, id: 'archived', title: 'Archived' }] : []),
    ...archived.map((sinkingFund) => ({ kind: 'sinking-fund' as const, sinkingFund })),
  ];

  function moveFund(sinkingFund: SinkingFund, offset: number) {
    if (sinkingFund.state !== 'ACTIVE') return;
    const group = [...active];
    const index = group.findIndex((candidate) => candidate.id === sinkingFund.id);
    const target = index + offset;
    if (index < 0 || target < 0 || target >= group.length) return;
    [group[index], group[target]] = [group[target], group[index]];
    reorderMutation.mutate(group.map((candidate) => candidate.id));
  }

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading Sinking Funds..." />
      </Screen>
    );
  }

  return (
    <Screen>
      <Stack.Screen options={{ headerShown: true, title: 'Sinking Funds' }} />
      <FlatList
        contentContainerStyle={styles.content}
        data={rows}
        keyExtractor={(item) => (item.kind === 'section' ? item.id : item.sinkingFund.id)}
        ListEmptyComponent={
          query.isError && !query.data ? (
            <ErrorState
              message={displayError(
                query.error,
                settings.currencyCode,
                'Check the API connection and try again.',
              )}
              retry={() => query.refetch()}
            />
          ) : (
            <EmptyState
              mascot="idle"
              message="Create persistent funds and link paycheck entries as contributions."
              title="No Sinking Funds yet"
            />
          )
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Sinking Funds</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {query.data?.summary.activeCount ?? 0} active
                </AppText>
              </View>
              <Button
                icon={Plus}
                label="New Sinking Fund"
                onPress={() => router.push('/sinking-funds/new')}
              />
            </View>
            <View
              style={[
                styles.summary,
                { backgroundColor: colors.surface, borderColor: colors.border },
              ]}
            >
              <AppText style={{ color: colors.muted }} variant="caption">
                Total active balance
              </AppText>
              <AppText variant="money">
                {formatMoney(
                  query.data?.summary.totalActiveBalanceMinor ?? 0,
                  settings.currencyCode,
                )}
              </AppText>
              <View style={styles.metrics}>
                <Metric label="Active" value={String(query.data?.summary.activeCount ?? 0)} />
                <Metric label="Archived" value={String(query.data?.summary.archivedCount ?? 0)} />
              </View>
            </View>
            <YuukaRefreshIndicator visible={query.isFetching && Boolean(query.data)} />
            {query.isError && query.data ? <StaleBanner /> : null}
            {reorderMutation.error ? (
              <AppText style={{ color: colors.danger }} variant="error">
                {displayError(
                  reorderMutation.error,
                  settings.currencyCode,
                  'Sinking Fund order was not saved.',
                )}
              </AppText>
            ) : null}
          </View>
        }
        refreshControl={
          <RefreshControl
            accessibilityLabel="Refresh Sinking Funds"
            colors={['transparent']}
            onRefresh={() => void query.refetch()}
            progressBackgroundColor="transparent"
            refreshing={query.isRefetching}
            tintColor="transparent"
          />
        }
        renderItem={({ item }) =>
          item.kind === 'section' ? (
            <AppText style={{ color: colors.muted }} variant="label">
              {item.title}
            </AppText>
          ) : (
            <View style={styles.fundRow}>
              <View style={styles.fundCard}>
                <SinkingFundCard
                  onPress={() => router.push(`/sinking-funds/${item.sinkingFund.id}`)}
                  sinkingFund={item.sinkingFund}
                />
              </View>
              {item.sinkingFund.state === 'ACTIVE' ? (
                <View style={styles.reorderActions}>
                  <IconButton
                    disabled={
                      reorderMutation.isPending ||
                      active.findIndex((fund) => fund.id === item.sinkingFund.id) === 0
                    }
                    icon={ArrowUp}
                    label={`Move ${item.sinkingFund.name} up`}
                    onPress={() => moveFund(item.sinkingFund, -1)}
                  />
                  <IconButton
                    disabled={
                      reorderMutation.isPending ||
                      active.findIndex((fund) => fund.id === item.sinkingFund.id) ===
                        active.length - 1
                    }
                    icon={ArrowDown}
                    label={`Move ${item.sinkingFund.name} down`}
                    onPress={() => moveFund(item.sinkingFund, 1)}
                  />
                </View>
              ) : null}
            </View>
          )
        }
      />
    </Screen>
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

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  fundCard: { flex: 1 },
  fundRow: { alignItems: 'center', flexDirection: 'row', gap: 10 },
  header: { gap: 13, marginBottom: 3 },
  metric: { flex: 1, gap: 3 },
  metrics: { flexDirection: 'row', gap: 12 },
  reorderActions: { gap: 6 },
  summary: { borderRadius: 8, borderWidth: 1, gap: 9, padding: 15 },
  titleBlock: { gap: 3 },
  titleRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
});
