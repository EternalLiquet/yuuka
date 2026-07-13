import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { Plus } from 'lucide-react-native';
import { FlatList, Pressable, RefreshControl, StyleSheet, View } from 'react-native';

import { BudgetTemplate } from '@/api/contracts';
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
import { formatMoney } from '@/domain/money';
import { useMinimumVisibleDuration } from '@/hooks/use-minimum-visible-duration';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export default function TemplatesScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const query = useQuery({ queryKey: ['templates'], queryFn: () => api.templates(false) });
  const showColdLoader = useMinimumVisibleDuration(query.isPending && !query.data, 1000);

  if (showColdLoader) {
    return (
      <Screen contentContainerStyle={styles.center}>
        <YuukaLoadingState message="Loading templates..." />
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
              action={
                <Button
                  icon={Plus}
                  label="New template"
                  onPress={() => router.push('/templates/new')}
                />
              }
              mascot="clipboard"
              message="Create reusable ordered allocations for repeat paychecks."
              title="No templates yet"
            />
          )
        }
        ListHeaderComponent={
          <View style={styles.header}>
            <View style={styles.titleRow}>
              <View style={styles.titleBlock}>
                <AppText variant="title">Templates</AppText>
                <AppText style={{ color: colors.muted }} variant="caption">
                  {query.data?.totalItems ?? 0} saved
                </AppText>
              </View>
              <Button
                icon={Plus}
                label="New template"
                onPress={() => router.push('/templates/new')}
              />
            </View>
            <YuukaRefreshIndicator visible={query.isFetching && Boolean(query.data)} />
            {query.isError && query.data ? <StaleBanner /> : null}
          </View>
        }
        refreshControl={
          <RefreshControl
            accessibilityLabel="Refresh templates"
            colors={['transparent']}
            onRefresh={() => void query.refetch()}
            progressBackgroundColor="transparent"
            refreshing={query.isRefetching}
            testID="templates-refresh-control"
            tintColor="transparent"
          />
        }
        renderItem={({ item }) => (
          <TemplateCard onPress={() => router.push(`/templates/${item.id}`)} template={item} />
        )}
      />
    </Screen>
  );
}

function TemplateCard({ onPress, template }: { onPress: () => void; template: BudgetTemplate }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  return (
    <Pressable
      accessibilityLabel={`Open template ${template.name}`}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.card,
        { backgroundColor: colors.surface, borderColor: colors.border },
        pressed && styles.pressed,
      ]}
    >
      <View style={styles.cardTitleRow}>
        <View style={styles.titleBlock}>
          <AppText numberOfLines={2} variant="label">
            {template.name}
          </AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {template.entryCount} {template.entryCount === 1 ? 'entry' : 'entries'} | Updated{' '}
            {formatDate(template.updatedAt)}
          </AppText>
        </View>
        <AppText variant="money">
          {formatMoney(template.defaultTotalMinor, settings.currencyCode)}
        </AppText>
      </View>
      {template.description ? (
        <AppText numberOfLines={2} style={{ color: colors.muted }} variant="caption">
          {template.description}
        </AppText>
      ) : null}
    </Pressable>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(
    new Date(value),
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: 8, borderWidth: 1, gap: 9, padding: 14 },
  cardTitleRow: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
  center: { alignItems: 'center', justifyContent: 'center' },
  content: { flexGrow: 1, gap: 12, padding: 16, paddingBottom: 28 },
  header: { gap: 13, marginBottom: 3 },
  pressed: { opacity: 0.74 },
  titleBlock: { flex: 1, gap: 3 },
  titleRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
});
