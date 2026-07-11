import type { LucideIcon } from 'lucide-react-native';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';

import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';

type IconButtonProps = {
  disabled?: boolean;
  icon: LucideIcon;
  label: string;
  onLongPress?: () => void;
  onPress: () => void;
  selected?: boolean;
};

export function IconButton({
  disabled,
  icon: Icon,
  label,
  onLongPress,
  onPress,
  selected,
}: IconButtonProps) {
  const { colors } = useAppTheme();
  const [hovered, setHovered] = useState(false);
  return (
    <View style={styles.wrapper}>
      <Pressable
        accessibilityLabel={label}
        accessibilityRole="button"
        accessibilityState={{ disabled, selected }}
        disabled={disabled}
        onHoverIn={() => setHovered(true)}
        onHoverOut={() => setHovered(false)}
        onLongPress={onLongPress}
        onPress={onPress}
        style={({ pressed }) => [
          styles.button,
          {
            backgroundColor: selected ? colors.accentSoft : colors.surfaceElevated,
            borderColor: selected ? colors.accent : colors.border,
          },
          pressed && styles.pressed,
          disabled && styles.disabled,
        ]}
      >
        <Icon color={selected ? colors.accent : colors.text} size={20} />
      </Pressable>
      {hovered ? (
        <View style={[styles.tooltip, { backgroundColor: colors.text }]} pointerEvents="none">
          <AppText style={{ color: colors.background }} variant="caption">
            {label}
          </AppText>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  button: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    height: 44,
    justifyContent: 'center',
    width: 44,
  },
  disabled: { opacity: 0.42 },
  pressed: { opacity: 0.7 },
  tooltip: {
    borderRadius: 4,
    left: '50%',
    paddingHorizontal: 8,
    paddingVertical: 5,
    position: 'absolute',
    top: 48,
    transform: [{ translateX: -24 }],
    zIndex: 20,
  },
  wrapper: { position: 'relative' },
});
