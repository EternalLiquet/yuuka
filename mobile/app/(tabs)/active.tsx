import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Plus } from 'lucide-react-native';
import { ActivityIndicator, FlatList, RefreshControl, StyleSheet, View } from 'react-native';

import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { Screen } from '@/components/screen';
import { EmptyState, ErrorState, StaleBanner } from '@/components/states';
import { PaycheckCard } from '@/features/paychecks/paycheck-card';
import { useAppTheme } from '@/theme/use-app-theme';

export default function ActiveScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const query = useQuery({
    queryKey: ['paychecks', 'active'],
    queryFn: api.activePaychecks,
  });

  return (
    <Screen>
      <FlatList
        contentContainerStyle={styles.content}
        data={query.data?.items ?? []}
        keyExtractor={(item) => item.id}
        ListEmptyComponent={
          query.isPending ? (
            <ActivityIndicator color={colors.accent} size="large" style={styles.loader} />
          ) : query.isError && !query.data ? (
            <ErrorState message={errorMessage(query.error)} retry={() => query.refetch()} />
          ) : (
            <EmptyState
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
        renderItem={({ item }) => (
          <PaycheckCard onPress={() => router.push(`/paychecks/${item.id}`)} paycheck={item} />
        )}
      />
    </Screen>
  );
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Check the API connection and try again.';
}

const styles = StyleSheet.create({
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  header: { gap: 13, marginBottom: 3 },
  loader: { marginTop: 80 },
  titleBlock: { gap: 3 },
  titleRow: { alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between' },
});
