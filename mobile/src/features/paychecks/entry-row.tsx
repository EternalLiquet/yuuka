import {
  ArrowDown,
  ArrowUp,
  GripVertical,
  History,
  Pencil,
  ReceiptText,
} from 'lucide-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { Entry } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { IconButton } from '@/components/icon-button';
import { StatusBadge } from '@/components/status-badge';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

const typeLabels = {
  BILL: 'Bill',
  SPENDING_BUCKET: 'Spending Bucket',
  SINKING_FUND: 'Sinking Fund',
} as const;

type EntryRowProps = {
  drag?: () => void;
  entry: Entry;
  isFirst?: boolean;
  isLast?: boolean;
  onEdit: () => void;
  onBucketActivity?: () => void;
  onHistory?: () => void;
  onMoveDown?: () => void;
  onMoveUp?: () => void;
  onStatusPress: () => void;
  reorderEnabled?: boolean;
};

export function EntryRow({
  drag,
  entry,
  isFirst,
  isLast,
  onEdit,
  onBucketActivity,
  onHistory,
  onMoveDown,
  onMoveUp,
  onStatusPress,
  reorderEnabled,
}: EntryRowProps) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const opensBucket = entry.entryType === 'SPENDING_BUCKET' && Boolean(onBucketActivity);
  return (
    <Pressable
      accessibilityLabel={opensBucket ? `Open bucket ledger for ${entry.name}` : undefined}
      accessibilityRole={opensBucket ? 'button' : undefined}
      disabled={!opensBucket}
      onPress={onBucketActivity}
      style={({ pressed }) => [
        styles.row,
        { borderBottomColor: colors.border },
        pressed && styles.pressed,
      ]}
    >
      <View style={styles.main}>
        <View style={styles.titleRow}>
          <View style={styles.titleBlock}>
            <AppText numberOfLines={2} variant="label">
              {entry.name}
            </AppText>
            <AppText style={{ color: colors.muted }} variant="caption">
              {typeLabels[entry.entryType]}
              {entry.dueDate ? `  |  Due ${formatDate(entry.dueDate)}` : ''}
            </AppText>
          </View>
          <AppText variant="money">{formatMoney(entry.amountMinor, settings.currencyCode)}</AppText>
        </View>

        <View style={styles.detailRow}>
          <Pressable
            accessibilityLabel={`Change status for ${entry.name}`}
            accessibilityRole="button"
            onPress={onStatusPress}
          >
            <StatusBadge status={entry.status} />
          </Pressable>
          {entry.entryType === 'SPENDING_BUCKET' ? (
            <AppText style={{ color: colors.muted, flex: 1, textAlign: 'right' }} variant="caption">
              {formatMoney(entry.spentMinor ?? 0, settings.currencyCode)} spent |{' '}
              {formatMoney(entry.remainingMinor ?? entry.amountMinor, settings.currencyCode)} left
            </AppText>
          ) : (
            <AppText style={{ color: colors.muted, marginLeft: 'auto' }} variant="caption">
              Edited {formatDateTime(entry.updatedAt)}
            </AppText>
          )}
        </View>
      </View>

      <View style={styles.actions}>
        {onHistory ? (
          <IconButton
            icon={History}
            label={`View status history for ${entry.name}`}
            onPress={onHistory}
          />
        ) : null}
        {entry.entryType === 'SPENDING_BUCKET' && onBucketActivity ? (
          <IconButton
            icon={ReceiptText}
            label={`Add activity to ${entry.name}`}
            onPress={onBucketActivity}
          />
        ) : null}
        {reorderEnabled ? (
          <>
            <IconButton
              disabled={isFirst}
              icon={ArrowUp}
              label={`Move ${entry.name} up`}
              onPress={onMoveUp ?? (() => undefined)}
            />
            <IconButton
              disabled={isLast}
              icon={ArrowDown}
              label={`Move ${entry.name} down`}
              onPress={onMoveDown ?? (() => undefined)}
            />
            <IconButton
              icon={GripVertical}
              label={`Drag ${entry.name}`}
              onLongPress={drag}
              onPress={drag ?? (() => undefined)}
            />
          </>
        ) : null}
        <IconButton icon={Pencil} label={`Edit ${entry.name}`} onPress={onEdit} />
      </View>
    </Pressable>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(
    new Date(`${value}T12:00:00`),
  );
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(
    new Date(value),
  );
}

const styles = StyleSheet.create({
  actions: { alignItems: 'center', flexDirection: 'row', gap: 6, justifyContent: 'flex-end' },
  detailRow: { alignItems: 'center', flexDirection: 'row', gap: 10 },
  main: { gap: 11 },
  pressed: { opacity: 0.74 },
  row: { borderBottomWidth: 1, gap: 12, paddingVertical: 15 },
  titleBlock: { flex: 1, gap: 3 },
  titleRow: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
});
