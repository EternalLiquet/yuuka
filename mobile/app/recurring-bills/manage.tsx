import { useQuery } from '@tanstack/react-query';
import { Stack, useRouter } from 'expo-router';
import { CalendarDays, Plus } from 'lucide-react-native';
import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import type { RecurringBill, RecurringBillStatusFilter } from '@/api/contracts';
import { displayError } from '@/api/display-error';
import { useYuukaApi } from '@/api/use-yuuka-api';
import { AppText } from '@/components/app-text';
import { Button } from '@/components/button';
import { ScrollScreen } from '@/components/scroll-screen';
import { SegmentedControl } from '@/components/segmented-control';
import { EmptyState, ErrorState, YuukaLoadingState } from '@/components/states';
import { TextField } from '@/components/text-field';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const filterOptions = [
  { label: 'Active', value: 'ACTIVE' },
  { label: 'Inactive', value: 'INACTIVE' },
  { label: 'All', value: 'ALL' },
] as const;

export default function ManageRecurringBillsScreen() {
  const api = useYuukaApi();
  const router = useRouter();
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const [filter, setFilter] = useState<RecurringBillStatusFilter>('ACTIVE');
  const [search, setSearch] = useState('');
  const query = useQuery({
    queryKey: ['recurring-bills', 'definitions'],
    queryFn: () => api.recurringBills('ALL'),
  });
  const definitions = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return (query.data?.items ?? []).filter(
      (item) =>
        (filter === 'ALL' || item.active === (filter === 'ACTIVE')) &&
        (!needle ||
          item.name.toLowerCase().includes(needle) ||
          item.payee?.toLowerCase().includes(needle) ||
          item.accountName?.toLowerCase().includes(needle)),
    );
  }, [filter, query.data?.items, search]);

  return (
    <>
      <Stack.Screen
        options={{
          headerShown: true,
          title: 'Manage Recurring Bills',
          headerStyle: { backgroundColor: colors.surface },
          headerTintColor: colors.text,
        }}
      />
      <ScrollScreen contentContainerStyle={styles.content}>
        <View style={styles.actions}>
          <Button
            icon={Plus}
            label="New recurring Bill"
            onPress={() => router.push('/recurring-bills/new')}
          />
          <Button
            icon={CalendarDays}
            label="Timeline"
            onPress={() => router.push('/recurring-bills')}
            variant="secondary"
          />
        </View>
        <TextField label="Search recurring Bills" onChangeText={setSearch} value={search} />
        <SegmentedControl
          label="Recurring Bill status filter"
          onChange={setFilter}
          options={filterOptions}
          value={filter}
        />
        {query.isPending ? <YuukaLoadingState message="Loading recurring Bills..." /> : null}
        {query.isError && !query.data ? (
          <ErrorState
            message={displayError(
              query.error,
              settings.currencyCode,
              'Recurring Bills could not load.',
            )}
            retry={() => query.refetch()}
          />
        ) : null}
        {!query.isPending && !definitions.length ? (
          <EmptyState
            message="Create a definition or adjust the current search and filter."
            title="No recurring Bills shown"
          />
        ) : null}
        {definitions.map((definition) => (
          <DefinitionRow
            definition={definition}
            key={definition.id}
            onPress={() => router.push(`/recurring-bills/${definition.id}`)}
          />
        ))}
      </ScrollScreen>
    </>
  );
}

function DefinitionRow({
  definition,
  onPress,
}: {
  definition: RecurringBill;
  onPress: () => void;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  return (
    <Pressable
      accessibilityLabel={`Open recurring Bill ${definition.name}`}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.row,
        { backgroundColor: colors.surface, borderColor: colors.border },
        pressed && styles.pressed,
      ]}
    >
      <View style={styles.rowText}>
        <AppText variant="label">{definition.name}</AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          Due day {definition.dueDay} ·{' '}
          {definition.paymentMethod === 'MANUAL' ? 'Manual' : 'Autopay'}
        </AppText>
      </View>
      <View style={styles.amount}>
        <AppText variant="money">
          {formatMoney(definition.typicalAmountMinor, settings.currencyCode)}
        </AppText>
        <AppText
          style={{ color: definition.active ? colors.posted : colors.muted }}
          variant="caption"
        >
          {definition.active ? 'Active' : 'Inactive'}
        </AppText>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  actions: { gap: 10 },
  amount: { alignItems: 'flex-end', gap: 4 },
  content: { gap: 14, paddingBottom: 36 },
  pressed: { opacity: 0.72 },
  row: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    flexDirection: 'row',
    gap: 12,
    minHeight: 72,
    padding: 14,
  },
  rowText: { flex: 1, gap: 5 },
});
