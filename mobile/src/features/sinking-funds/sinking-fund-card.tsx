import { Pressable, StyleSheet, View } from 'react-native';

import { SinkingFund } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { ProgressBar } from '@/components/progress-bar';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export function SinkingFundCard({
  onPress,
  sinkingFund,
}: {
  onPress: () => void;
  sinkingFund: SinkingFund;
}) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const currency = settings.currencyCode;
  const progress =
    sinkingFund.progressPercent == null ? null : Math.round(sinkingFund.progressPercent);
  return (
    <Pressable
      accessibilityLabel={`${sinkingFund.name}, ${formatMoney(
        sinkingFund.currentBalanceMinor,
        currency,
      )} saved${progress == null ? '' : `, ${progress} percent funded`}`}
      onPress={onPress}
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
    >
      <View style={styles.header}>
        <View style={styles.titleBlock}>
          <AppText variant="label">{sinkingFund.name}</AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {sinkingFund.targetDate
              ? `Target ${formatDate(sinkingFund.targetDate)}`
              : 'No date target'}
          </AppText>
        </View>
        <View
          style={[
            styles.badge,
            {
              backgroundColor:
                sinkingFund.state === 'ACTIVE' ? colors.postedSoft : colors.surfaceElevated,
            },
          ]}
        >
          <AppText
            style={{ color: sinkingFund.state === 'ACTIVE' ? colors.posted : colors.muted }}
            variant="caption"
          >
            {sinkingFund.state === 'ACTIVE' ? 'Active' : 'Archived'}
          </AppText>
        </View>
      </View>
      <View style={styles.amountBlock}>
        <AppText variant="money">
          {formatMoney(sinkingFund.currentBalanceMinor, currency)} saved
        </AppText>
        {sinkingFund.targetMinor == null ? (
          <AppText style={{ color: colors.muted }} variant="caption">
            No amount target
          </AppText>
        ) : (
          <AppText style={{ color: colors.muted }} variant="caption">
            Target: {formatMoney(sinkingFund.targetMinor, currency)}
          </AppText>
        )}
      </View>
      {sinkingFund.progressPercent == null ? null : (
        <ProgressBar
          accessibilityLabel={`${progress ?? 0} percent funded`}
          tone="posted"
          value={sinkingFund.progressPercent}
        />
      )}
      <AppText style={{ color: colors.muted }} variant="caption">
        {sinkingFund.transactionCount} transaction{sinkingFund.transactionCount === 1 ? '' : 's'}
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
