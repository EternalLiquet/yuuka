import { CircleCheck, CircleDashed, Clock3 } from 'lucide-react-native';
import { StyleSheet, View } from 'react-native';

import { EntryStatus } from '@/api/contracts';
import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';

export const statusLabels: Record<EntryStatus, string> = {
  NOT_PAID: 'Not Paid',
  PROCESSING: 'Processing',
  POSTED: 'Posted',
};

export function StatusBadge({ status }: { status: EntryStatus }) {
  const { colors } = useAppTheme();
  const config = {
    NOT_PAID: { Icon: CircleDashed, color: colors.muted, background: colors.surfaceElevated },
    PROCESSING: {
      Icon: Clock3,
      color: colors.processing,
      background: colors.processingSoft,
    },
    POSTED: { Icon: CircleCheck, color: colors.posted, background: colors.postedSoft },
  }[status];
  const Icon = config.Icon;
  return (
    <View
      accessible
      accessibilityLabel={`Status: ${statusLabels[status]}`}
      style={[styles.badge, { backgroundColor: config.background }]}
    >
      <Icon color={config.color} size={15} />
      <AppText style={{ color: config.color }} variant="caption">
        {statusLabels[status]}
      </AppText>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    alignItems: 'center',
    alignSelf: 'flex-start',
    borderRadius: 6,
    flexDirection: 'row',
    gap: 6,
    minHeight: 28,
    paddingHorizontal: 8,
  },
});
