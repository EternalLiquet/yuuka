import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Search } from 'lucide-react-native';
import { useMemo, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';

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
import { TextField } from '@/components/text-field';
import { PaycheckCard } from '@/features/paychecks/paycheck-card';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const sortOptions = [
  { label: 'Newest', value: 'newest' },
  { label: 'Oldest', value: 'oldest' },
] as const;

export default function HistoryScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [sort, setSort] = useState<'newest' | 'oldest'>('newest');
  const queryString = useMemo(() => {
    const params = new URLSearchParams();
    if (search.trim()) params.set('search', search.trim());
    if (/^\d{4}-\d{2}-\d{2}$/.test(from)) params.set('from', from);
    if (/^\d{4}-\d{2}-\d{2}$/.test(to)) params.set('to', to);
    params.set('oldestFirst', String(sort === 'oldest'));
    return `&${params.toString()}`;
  }, [from, search, sort, to]);
  const query = useQuery({
    queryKey: ['paychecks', 'history', queryString],
    queryFn: () => api.historyPaychecks(queryString),
  });
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 1000);

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading history..." />
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
                'Check the API connection and try again.',
              )}
              retry={() => query.refetch()}
            />
          ) : (
            <EmptyState
              mascot="clipboard"
              message="Closed and completed paychecks will appear here."
              title="No history found"
            />
          )
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleBlock}>
              <AppText variant="title">History</AppText>
              <AppText style={{ color: colors.muted }} variant="caption">
                {query.data?.totalItems ?? 0} result{query.data?.totalItems === 1 ? '' : 's'}
              </AppText>
            </View>
            <Button
              icon={Search}
              label="Find entry"
              onPress={() => router.push('/search/entries?scope=HISTORY')}
              variant="secondary"
            />
            <View style={styles.searchBlock}>
              <Search color={colors.muted} size={19} style={styles.searchIcon} />
              <TextField
                autoCapitalize="none"
                containerStyle={styles.searchField}
                label="Search"
                onChangeText={setSearch}
                placeholder="Name or source"
                value={search}
              />
            </View>
            <View style={styles.dateRow}>
              <TextField
                containerStyle={styles.dateField}
                label="From"
                onChangeText={setFrom}
                placeholder="YYYY-MM-DD"
                value={from}
              />
              <TextField
                containerStyle={styles.dateField}
                label="To"
                onChangeText={setTo}
                placeholder="YYYY-MM-DD"
                value={to}
              />
            </View>
            <SegmentedControl
              label="History sort"
              onChange={setSort}
              options={sortOptions}
              value={sort}
            />
            <YuukaRefreshIndicator visible={query.isFetching && Boolean(query.data)} />
            {query.isError && query.data ? <StaleBanner /> : null}
          </View>
        }
        refreshControl={
          <RefreshControl
            colors={['transparent']}
            onRefresh={() => void query.refetch()}
            progressBackgroundColor="transparent"
            refreshing={query.isRefetching}
            tintColor="transparent"
          />
        }
        renderItem={({ item }) => (
          <PaycheckCard onPress={() => router.push(`/paychecks/${item.id}`)} paycheck={item} />
        )}
      />
    </Screen>
  );
}

const styles = StyleSheet.create({
  center: { alignItems: 'center', justifyContent: 'center' },
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  dateField: { flex: 1 },
  dateRow: { flexDirection: 'row', gap: 10 },
  header: { gap: 14, marginBottom: 3 },
  searchBlock: { position: 'relative' },
  searchField: { flex: 1 },
  searchIcon: { position: 'absolute', right: 13, top: 39, zIndex: 2 },
  titleBlock: { gap: 3 },
});
