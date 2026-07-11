import type { LucideIcon } from 'lucide-react-native';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';

import { useAppTheme } from '@/theme/use-app-theme';

import { AppText } from './app-text';

type ButtonProps = {
  accessibilityLabel?: string;
  disabled?: boolean;
  icon?: LucideIcon;
  label: string;
  loading?: boolean;
  onPress: () => void;
  variant?: 'danger' | 'ghost' | 'primary' | 'secondary';
};

export function Button({
  accessibilityLabel,
  disabled,
  icon: Icon,
  label,
  loading,
  onPress,
  variant = 'primary',
}: ButtonProps) {
  const { colors } = useAppTheme();
  const palette = {
    primary: { background: colors.accent, border: colors.accent, text: colors.accentText },
    secondary: { background: colors.surfaceElevated, border: colors.border, text: colors.text },
    danger: { background: colors.dangerSoft, border: colors.danger, text: colors.danger },
    ghost: { background: 'transparent', border: 'transparent', text: colors.accent },
  }[variant];

  return (
    <Pressable
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityRole="button"
      accessibilityState={{ disabled: Boolean(disabled || loading), busy: Boolean(loading) }}
      disabled={disabled || loading}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: palette.background, borderColor: palette.border },
        pressed && styles.pressed,
        (disabled || loading) && styles.disabled,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={palette.text} size="small" />
      ) : (
        <View style={styles.content}>
          {Icon ? <Icon color={palette.text} size={18} strokeWidth={2} /> : null}
          <AppText style={[styles.label, { color: palette.text }]}>{label}</AppText>
        </View>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    alignItems: 'center',
    borderRadius: 8,
    borderWidth: 1,
    justifyContent: 'center',
    minHeight: 46,
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  content: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'center',
  },
  disabled: { opacity: 0.5 },
  label: { fontSize: 15, fontWeight: '700', lineHeight: 20 },
  pressed: { opacity: 0.74 },
});
