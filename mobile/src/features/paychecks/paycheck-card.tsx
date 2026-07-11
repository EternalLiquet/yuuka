import { ChevronRight } from 'lucide-react-native';
import { Pressable, StyleSheet, View } from 'react-native';

import { Paycheck } from '@/api/contracts';
import { AppText } from '@/components/app-text';
import { ProgressBar } from '@/components/progress-bar';
import { formatMoney } from '@/domain/money';
import { useSettings } from '@/settings/settings-provider';
import { useAppTheme } from '@/theme/use-app-theme';

export function PaycheckCard({ onPress, paycheck }: { onPress: () => void; paycheck: Paycheck }) {
  const { colors } = useAppTheme();
  const { settings } = useSettings();
  const currency = settings.currencyCode;
  return (
    <Pressable
      accessibilityLabel={`${paycheck.name}, ${formatMoney(paycheck.amountMinor, currency)}, ${formatMoney(paycheck.unallocatedMinor, currency)} unallocated`}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.card,
        { backgroundColor: colors.surface, borderColor: colors.border },
        pressed && styles.pressed,
      ]}
    >
      <View style={styles.heading}>
        <View style={styles.headingText}>
          <AppText numberOfLines={2} variant="label">
            {paycheck.name}
          </AppText>
          <AppText style={{ color: colors.muted }} variant="caption">
            {formatDate(paycheck.incomeDate)}
          </AppText>
        </View>
        <View style={styles.amountBlock}>
          <AppText variant="money">{formatMoney(paycheck.amountMinor, currency)}</AppText>
          <ChevronRight color={colors.muted} size={19} />
        </View>
      </View>

      <View style={styles.metricRow}>
        <Metric
          label="Unallocated"
          tone={paycheck.unallocatedMinor === 0 ? colors.posted : colors.processing}
          value={formatMoney(paycheck.unallocatedMinor, currency)}
        />
        <Metric label="Allocated" value={`${paycheck.allocationPercent.toFixed(0)}%`} />
        <Metric label="Posted" value={`${paycheck.completionPercent.toFixed(0)}%`} />
      </View>

      <View style={styles.progressGroup}>
        <ProgressBar
          accessibilityLabel={`${paycheck.allocationPercent}% allocated`}
          value={paycheck.allocationPercent}
        />
        <ProgressBar
          accessibilityLabel={`${paycheck.completionPercent}% posted`}
          tone="posted"
          value={paycheck.completionPercent}
        />
      </View>

      <View style={styles.counts}>
        <Count color={colors.muted} label="Not Paid" value={paycheck.notPaidCount} />
        <Count color={colors.processing} label="Processing" value={paycheck.processingCount} />
        <Count color={colors.posted} label="Posted" value={paycheck.postedCount} />
        <AppText style={[styles.edited, { color: colors.muted }]} variant="caption">
          Edited {relativeDate(paycheck.updatedAt)}
        </AppText>
      </View>
    </Pressable>
  );
}

function Metric({ label, tone, value }: { label: string; tone?: string; value: string }) {
  const { colors } = useAppTheme();
  return (
    <View style={styles.metric}>
      <AppText style={{ color: colors.muted }} variant="caption">
        {label}
      </AppText>
      <AppText style={{ color: tone ?? colors.text, fontWeight: '700' }} variant="caption">
        {value}
      </AppText>
    </View>
  );
}

function Count({ color, label, value }: { color: string; label: string; value: number }) {
  return (
    <View accessibilityLabel={`${value} ${label}`} style={styles.count}>
      <View style={[styles.dot, { backgroundColor: color }]} />
      <AppText variant="caption">{value}</AppText>
    </View>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  }).format(new Date(`${value}T12:00:00`));
}

function relativeDate(value: string) {
  const date = new Date(value);
  return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' }).format(date);
}

const styles = StyleSheet.create({
  amountBlock: { alignItems: 'center', flexDirection: 'row', gap: 6 },
  card: { borderRadius: 8, borderWidth: 1, gap: 14, padding: 15 },
  count: { alignItems: 'center', flexDirection: 'row', gap: 5 },
  counts: { alignItems: 'center', flexDirection: 'row', gap: 12 },
  dot: { borderRadius: 4, height: 7, width: 7 },
  edited: { marginLeft: 'auto' },
  heading: {
    alignItems: 'flex-start',
    flexDirection: 'row',
    gap: 10,
    justifyContent: 'space-between',
  },
  headingText: { flex: 1, gap: 3 },
  metric: { flex: 1, gap: 3 },
  metricRow: { flexDirection: 'row', gap: 8 },
  pressed: { opacity: 0.78 },
  progressGroup: { gap: 5 },
});
