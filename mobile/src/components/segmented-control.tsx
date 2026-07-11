import { Pressable, ScrollView, StyleSheet } from 'react-native';

import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';

type Option<T extends string> = { label: string; value: T };

export function SegmentedControl<T extends string>({
  label,
  onChange,
  options,
  value,
}: {
  label: string;
  onChange: (value: T) => void;
  options: readonly Option<T>[];
  value: T;
}) {
  const { colors } = useAppTheme();
  return (
    <ScrollView
      accessibilityLabel={label}
      contentContainerStyle={styles.row}
      horizontal
      showsHorizontalScrollIndicator={false}
    >
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <Pressable
            accessibilityLabel={option.label}
            accessibilityRole="radio"
            accessibilityState={{ checked: selected }}
            key={option.value}
            onPress={() => onChange(option.value)}
            style={({ pressed }) => [
              styles.option,
              {
                backgroundColor: selected ? colors.accentSoft : colors.surfaceElevated,
                borderColor: selected ? colors.accent : colors.border,
              },
              pressed && styles.pressed,
            ]}
          >
            <AppText style={{ color: selected ? colors.accent : colors.muted }} variant="caption">
              {option.label}
            </AppText>
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  option: {
    borderRadius: 7,
    borderWidth: 1,
    minHeight: 38,
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  pressed: { opacity: 0.72 },
  row: { gap: 7 },
});
