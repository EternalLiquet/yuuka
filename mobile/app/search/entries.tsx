import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { Stack, useLocalSearchParams, useRouter } from 'expo-router';
import { Search } from 'lucide-react-native';
import { useEffect, useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';

import type { EntrySearchResult, SearchScope } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { SegmentedControl } from '@/components/segmented-control';
import { EmptyState, ErrorState, StaleBanner } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney } from '@/domain/money';
import { buildEntrySearchCriteria } from '@/features/search/entry-search';
import type { EntrySearchMode } from '@/features/search/entry-search';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const modeOptions = [
  { label: 'All', value: 'ALL' },
  { label: 'Name', value: 'NAME' },
  { label: 'Amount', value: 'AMOUNT' },
] as const;

const scopeOptions = [
  { label: 'All checks', value: 'ALL' },
  { label: 'Active', value: 'ACTIVE' },
  { label: 'History', value: 'HISTORY' },
] as const;

export default function EntrySearchScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const params = useLocalSearchParams<{ scope?: SearchScope }>();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [rawQuery, setRawQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [mode, setMode] = useState<EntrySearchMode>('ALL');
  const [scope, setScope] = useState<SearchScope>(validScope(params.scope) ?? 'ALL');

  useEffect(() => {
    const timeout = setTimeout(() => setDebouncedQuery(rawQuery), 300);
    return () => clearTimeout(timeout);
  }, [rawQuery]);

  const criteria = useMemo(
    () => buildEntrySearchCriteria(debouncedQuery, mode),
    [debouncedQuery, mode],
  );
  const criteriaError = criteria && 'error' in criteria ? (criteria.error ?? '') : '';
  const activeCriteria = criteria && !criteriaError ? criteria : null;
  const query = useInfiniteQuery({
    enabled: Boolean(activeCriteria),
    getNextPageParam: (lastPage) => (lastPage.hasNext ? lastPage.page + 1 : undefined),
    initialPageParam: 0,
    placeholderData: keepPreviousData,
    queryFn: ({ pageParam }) => {
      if (!activeCriteria) throw new Error('Search criteria are required.');
      return api.searchEntries({
        amountMinor: activeCriteria.amountMinor,
        page: Number(pageParam),
        query: activeCriteria.query,
        scope,
      });
    },
    queryKey: [
      'search',
      'entries',
      scope,
      activeCriteria?.query ?? null,
      activeCriteria?.amountMinor ?? null,
    ],
  });
  const results = query.data?.pages.flatMap((page) => page.items) ?? [];
  const totalItems = query.data?.pages[0]?.totalItems ?? 0;
  const isInitial = !rawQuery.trim();
  const validation = rawQuery.trim() ? criteriaError : '';

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
          title: 'Find entry',
        }}
      />
      <Screen>
        <FlatList
          contentContainerStyle={styles.content}
          data={results}
          keyExtractor={(item) => item.entryId}
          ListEmptyComponent={
            <SearchState
              error={query.error}
              isError={query.isError}
              isInitial={isInitial}
              isLoading={query.isPending && Boolean(activeCriteria)}
              retry={() => query.refetch()}
              validation={validation}
            />
          }
          ListFooterComponent={
            results.length > 0 && query.hasNextPage ? (
              <Button
                label="Load more"
                loading={query.isFetchingNextPage}
                onPress={() => void query.fetchNextPage()}
                variant="secondary"
              />
            ) : null
          }
          ListHeaderComponent={
            <View style={styles.header}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Find entry</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {totalItems} result{totalItems === 1 ? '' : 's'}
                </AppText>
              </View>
              <View style={styles.searchBlock}>
                <Search color={colors.muted} size={19} style={styles.searchIcon} />
                <TextField
                  autoCapitalize="none"
                  autoCorrect={false}
                  error={validation}
                  keyboardType={mode === 'AMOUNT' ? 'decimal-pad' : 'default'}
                  label={mode === 'AMOUNT' ? 'Amount' : 'Search'}
                  onChangeText={setRawQuery}
                  placeholder={mode === 'AMOUNT' ? '$13.99' : 'Netflix or $13.99'}
                  value={rawQuery}
                />
              </View>
              <SegmentedControl
                label="Search mode"
                onChange={setMode}
                options={modeOptions}
                value={mode}
              />
              <SegmentedControl
                label="Search scope"
                onChange={setScope}
                options={scopeOptions}
                value={scope}
              />
              {query.isFetching && results.length ? (
                <AppText
                  accessibilityLiveRegion="polite"
                  style={{ color: colors.muted }}
                  variant="caption"
                >
                  Refreshing results...
                </AppText>
              ) : null}
              {query.isError && results.length ? <StaleBanner /> : null}
            </View>
          }
          refreshControl={
            <RefreshControl
              colors={['transparent']}
              enabled={Boolean(activeCriteria)}
              onRefresh={() => void query.refetch()}
              progressBackgroundColor="transparent"
              refreshing={query.isRefetching}
              tintColor="transparent"
            />
          }
          renderItem={({ item }) => <ResultRow item={item} />}
        />
      </Screen>
    </>
  );

  function ResultRow({ item }: { item: EntrySearchResult }) {
    return (
      <Pressable
        accessibilityLabel={`Open ${item.entryName} in ${item.paycheckName}`}
        accessibilityRole="button"
        onPress={() =>
          router.push({
            pathname: '/paychecks/[id]',
            params: { highlightEntryId: item.entryId, id: item.paycheckId },
          })
        }
        style={({ pressed }) => [
          styles.result,
          { backgroundColor: colors.surface, borderColor: colors.border },
          pressed && styles.pressed,
        ]}
      >
        <View style={styles.resultTop}>
          <View style={styles.resultTitle}>
            <AppText numberOfLines={2} variant="label">
              {item.entryName}
            </AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {typeLabel(item.entryType)} | {statusLabel(item.status)}
            </AppText>
          </View>
          <AppText variant="money">{formatMoney(item.amountMinor, settings.currencyCode)}</AppText>
        </View>
        <AppText style={{ color: colors.muted }} variant="caption">
          {item.paycheckName} | {formatDate(item.paycheckIncomeDate)} |{' '}
          {contextLabel(item.paycheckContext)}
        </AppText>
      </Pressable>
    );
  }

  function SearchState({
    error,
    isError,
    isInitial,
    isLoading,
    retry,
    validation,
  }: {
    error: Error | null;
    isError: boolean;
    isInitial: boolean;
    isLoading: boolean;
    retry: () => void;
    validation: string;
  }) {
    if (validation) {
      return <EmptyState message={validation} title="Check the amount" />;
    }
    if (isInitial) {
      return <EmptyState message="Search by entry name or exact amount." title="Start a search" />;
    }
    if (isLoading) {
      return (
        <View
          accessibilityLabel="Searching entries"
          accessibilityLiveRegion="polite"
          accessibilityRole="progressbar"
          accessibilityState={{ busy: true }}
          style={styles.inlineState}
        >
          <AppText style={{ color: colors.muted }} variant="caption">
            Searching entries...
          </AppText>
        </View>
      );
    }
    if (isError) {
      return (
        <ErrorState
          message={displayError(error, settings.currencyCode, 'Search could not be completed.')}
          retry={retry}
        />
      );
    }
    return <EmptyState message="Try a different name or amount." title="No entries found" />;
  }
}

function validScope(value: SearchScope | undefined) {
  return value === 'ACTIVE' || value === 'HISTORY' || value === 'ALL' ? value : null;
}

function typeLabel(value: EntrySearchResult['entryType']) {
  return {
    BILL: 'Bill',
    SINKING_FUND: 'Sinking Fund',
    SPENDING_BUCKET: 'Spending Bucket',
  }[value];
}

function statusLabel(value: EntrySearchResult['status']) {
  return {
    NOT_PAID: 'Not Paid',
    POSTED: 'Posted',
    PROCESSING: 'Processing',
  }[value];
}

function contextLabel(value: EntrySearchResult['paycheckContext']) {
  return value === 'ACTIVE' ? 'Active' : 'History';
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${value}T12:00:00`));
}

const styles = StyleSheet.create({
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  header: { gap: 14, marginBottom: 3 },
  inlineState: { alignItems: 'center', minHeight: 180, justifyContent: 'center', padding: 24 },
  pressed: { opacity: 0.74 },
  result: { borderRadius: 8, borderWidth: 1, gap: 10, padding: 13 },
  resultTitle: { flex: 1, gap: 3 },
  resultTop: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
  searchBlock: { position: 'relative' },
  searchIcon: { position: 'absolute', right: 13, top: 39, zIndex: 2 },
  titleBlock: { gap: 3 },
});
