import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Plus } from 'lucide-react-native';
import { ActivityIndicator, FlatList, RefreshControl, StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { Payback } from '@/api/contracts';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { EmptyState, ErrorState, StaleBanner } from '@/components/states';
import { formatMoney } from '@/domain/money';
import { PaybackCard } from '@/features/paybacks/payback-card';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

type Row = { kind: 'section'; id: string; title: string } | { kind: 'payback'; payback: Payback };

export default function PaybacksScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const query = useQuery({ queryKey: ['paybacks'], queryFn: api.paybacks });
  const active = query.data?.items.filter((payback) => payback.state === 'ACTIVE') ?? [];
  const paidOff = query.data?.items.filter((payback) => payback.state === 'PAID_OFF') ?? [];
  const rows: Row[] = [
    ...(active.length
      ? [{ kind: 'section' as const, id: 'active', title: 'Active Paybacks' }]
      : []),
    ...active.map((payback) => ({ kind: 'payback' as const, payback })),
    ...(paidOff.length ? [{ kind: 'section' as const, id: 'paid-off', title: 'Paid Off' }] : []),
    ...paidOff.map((payback) => ({ kind: 'payback' as const, payback })),
  ];

  return (
    <Screen>
      <FlatList
        contentContainerStyle={styles.content}
        data={rows}
        keyExtractor={(item) => (item.kind === 'section' ? item.id : item.payback.id)}
        ListEmptyComponent={
          query.isPending ? (
            <ActivityIndicator color={colors.accent} size="large" style={styles.loader} />
          ) : query.isError && !query.data ? (
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
              message="Track money you borrowed from yourself and repay it through paycheck entries."
              title="No Paybacks yet"
            />
          )
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Paybacks</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {query.data?.summary.activeCount ?? 0} active
                </AppText>
              </View>
              <Button
                icon={Plus}
                label="New Payback"
                onPress={() => router.push('/paybacks/new')}
              />
            </View>
            <View
              style={[
                styles.summary,
                { backgroundColor: colors.surface, borderColor: colors.border },
              ]}
            >
              <AppText style={{ color: colors.muted }} variant="caption">
                Total left to pay back
              </AppText>
              <AppText variant="money">
                {formatMoney(query.data?.summary.totalRemainingMinor ?? 0, settings.currencyCode)}
              </AppText>
              <View style={styles.metrics}>
                <Metric
                  label="Originally tracked"
                  value={formatMoney(
                    query.data?.summary.totalOriginalMinor ?? 0,
                    settings.currencyCode,
                  )}
                />
                <Metric
                  label="Repaid in Yuuka"
                  value={formatMoney(
                    query.data?.summary.totalRepaidMinor ?? 0,
                    settings.currencyCode,
                  )}
                />
              </View>
            </View>
            {query.isError && query.data ? <StaleBanner /> : null}
          </View>
        }
        refreshControl={
          <RefreshControl
            colors={[colors.accent]}
            onRefresh={() => query.refetch()}
            refreshing={query.isRefetching}
            tintColor={colors.accent}
          />
        }
        renderItem={({ item }) =>
          item.kind === 'section' ? (
            <AppText style={{ color: colors.muted }} variant="label">
              {item.title}
            </AppText>
          ) : (
            <PaybackCard
              onPress={() => router.push(`/paybacks/${item.payback.id}`)}
              payback={item.payback}
            />
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
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  header: { gap: 13, marginBottom: 3 },
  loader: { marginTop: 80 },
  metric: { flex: 1, gap: 3 },
  metrics: { flexDirection: 'row', gap: 12 },
  summary: { borderRadius: 8, borderWidth: 1, gap: 9, padding: 15 },
  titleBlock: { gap: 3 },
  titleRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
});
