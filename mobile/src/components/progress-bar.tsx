import { DimensionValue, StyleSheet, View } from 'react-native';

import { useAppTheme } from '@/theme/use-app-theme';

export function ProgressBar({
  accessibilityLabel,
  tone = 'accent',
  value,
}: {
  accessibilityLabel: string;
  tone?: 'accent' | 'posted';
  value: number;
}) {
  const { colors } = useAppTheme();
  const normalized = Math.min(Math.max(value, 0), 100);
  return (
    <View
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="progressbar"
      accessibilityValue={{ min: 0, max: 100, now: normalized }}
      style={[styles.track, { backgroundColor: colors.surfaceElevated }]}
    >
      <View
        style={[
          styles.fill,
          {
            backgroundColor: tone === 'posted' ? colors.posted : colors.accent,
            width: `${normalized}%`,
          },
        ]}
      />
    </View>
  );
}

export function PaymentProgressBar({
  allocatedMinor,
  notPaidMinor,
  postedMinor,
  processingMinor,
}: {
  allocatedMinor: number;
  notPaidMinor: number;
  postedMinor: number;
  processingMinor: number;
}) {
  const { colors } = useAppTheme();
  const statusTotalMinor =
    Math.max(postedMinor, 0) + Math.max(processingMinor, 0) + Math.max(notPaidMinor, 0);
  const denominator = Math.max(allocatedMinor, statusTotalMinor, 0);
  const postedWidth = percentage(postedMinor, denominator);
  const processingWidth = Math.min(percentage(processingMinor, denominator), 100 - postedWidth);
  const postedLabel = Math.round(postedWidth);
  const processingLabel = Math.round(processingWidth);
  const notPaidLabel = denominator === 0 ? 0 : Math.max(0, 100 - postedLabel - processingLabel);

  return (
    <View
      accessibilityLabel={`${postedLabel}% posted, ${processingLabel}% processing, ${notPaidLabel}% not paid`}
      accessibilityRole="progressbar"
      accessibilityValue={{ min: 0, max: 100, now: Math.min(postedWidth + processingWidth, 100) }}
      style={[styles.track, { backgroundColor: colors.surfaceElevated }]}
    >
      {postedWidth > 0 ? (
        <View
          testID="payment-progress-posted"
          style={[
            styles.segment,
            { backgroundColor: colors.posted, width: percentWidth(postedWidth) },
          ]}
        />
      ) : null}
      {processingWidth > 0 ? (
        <View
          testID="payment-progress-processing"
          style={[
            styles.segment,
            { backgroundColor: colors.processing, width: percentWidth(processingWidth) },
          ]}
        />
      ) : null}
    </View>
  );
}

function percentage(amountMinor: number, denominatorMinor: number) {
  if (denominatorMinor <= 0) return 0;
  return Math.min(Math.max((Math.max(amountMinor, 0) / denominatorMinor) * 100, 0), 100);
}

function percentWidth(value: number): DimensionValue {
  return `${Number(value.toFixed(4))}%`;
}

const styles = StyleSheet.create({
  fill: { height: '100%' },
  segment: { height: '100%' },
  track: { borderRadius: 3, flexDirection: 'row', height: 6, overflow: 'hidden', width: '100%' },
});
