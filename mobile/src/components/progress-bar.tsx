import { StyleSheet, View } from 'react-native';

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

const styles = StyleSheet.create({
  fill: { height: '100%' },
  track: { borderRadius: 3, height: 6, overflow: 'hidden', width: '100%' },
});
