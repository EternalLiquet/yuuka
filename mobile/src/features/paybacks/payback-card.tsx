import { Pressable, StyleSheet, View } from 'react-native';

import { Payback } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { ProgressBar } from '@/components/progress-bar';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export function PaybackCard({ onPress, payback }: { onPress: () => void; payback: Payback }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const progress = Math.round(payback.progressPercent);
  const currency = settings.currencyCode;
  return (
    <Pressable
      accessibilityLabel={`${payback.name}, ${formatMoney(payback.remainingMinor, currency)} left, ${progress} percent repaid since tracking began`}
      onPress={onPress}
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
    >
      <View style={styles.header}>
        <View style={styles.titleBlock}>
          <AppText variant="label">{payback.name}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            Started {formatDate(payback.borrowedDate)}
            {payback.source ? ` | ${payback.source}` : ''}
          </AppText>
        </View>
        <View
          style={[
            styles.badge,
            {
              backgroundColor:
                payback.state === 'PAID_OFF' ? colors.postedSoft : colors.surfaceElevated,
            },
          ]}
        >
          <AppText
            style={{ color: payback.state === 'PAID_OFF' ? colors.posted : colors.muted }}
            variant="caption"
          >
            {payback.state === 'PAID_OFF' ? 'Paid Off' : 'Active'}
          </AppText>
        </View>
      </View>
      <View style={styles.amountBlock}>
        <AppText variant="money">{formatMoney(payback.remainingMinor, currency)} left</AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          Originally owed: {formatMoney(payback.originalAmountMinor, currency)}
        </AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          Tracked from: {formatMoney(payback.openingRemainingAmountMinor, currency)}
        </AppText>
        <AppText style={{ color: colors.muted }} variant="caption">
          Repaid in Yuuka: {formatMoney(payback.repaidMinor, currency)}
        </AppText>
      </View>
      <ProgressBar
        accessibilityLabel={`${progress} percent repaid since tracking began, ${formatMoney(payback.remainingMinor, currency)} remaining`}
        tone="posted"
        value={payback.progressPercent}
      />
      <AppText style={{ color: colors.muted }} variant="caption">
        {payback.repaymentCount} repayment{payback.repaymentCount === 1 ? '' : 's'}
      </AppText>
    </Pressable>
  );
}

function formatDate(value: string) {
  return new Date(`${value}T00:00:00`).toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
}

const styles = StyleSheet.create({
  amountBlock: { gap: 4 },
  badge: { borderRadius: 6, paddingHorizontal: 9, paddingVertical: 5 },
  card: { borderRadius: 8, borderWidth: 1, gap: 13, padding: 15 },
  header: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 12,
    justifyContent: 'space-between',
  },
  titleBlock: { flex: 1, gap: 3 },
});
