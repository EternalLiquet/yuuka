import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Plus } from 'lucide-react-native';
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native';

import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import {
  EmptyState,
  ErrorState,
  StaleBanner,
  YuukaLoadingState,
  YuukaRefreshIndicator,
} from '@/components/states';
import { PaycheckCard } from '@/features/paychecks/paycheck-card';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function ActiveScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const query = useQuery({
    queryKey: ['paychecks', 'active'],
    queryFn: api.activePaychecks,
  });
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 1000);

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading paychecks..." />
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
              mascot="wave"
              message="New and reopened paychecks will appear here."
              title="Nothing needs attention"
            />
          )
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Active</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {query.data?.totalItems ?? 0} paycheck{query.data?.totalItems === 1 ? '' : 's'}
                </AppText>
              </View>
              <Button
                icon={Plus}
                label="New paycheck"
                onPress={() => router.push('/paychecks/new')}
              />
            </View>
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
  header: { gap: 13, marginBottom: 3 },
  titleBlock: { gap: 3 },
  titleRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
});
