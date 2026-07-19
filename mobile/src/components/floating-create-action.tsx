import type { LucideIcon } from 'lucide-react-native';
import { Plus } from 'lucide-react-native';
import { Pressable, StyleSheet } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useAppTheme } from '@/theme/use-app-theme';

const ACTION_SIZE = 58;
const ACTION_MARGIN = 18;
const CONTENT_CLEARANCE = 34;

type FloatingCreateActionProps = {
  icon?: LucideIcon;
  label: string;
  onPress: () => void;
  testID?: string;
};

export function useFloatingCreateActionBottomPadding(extraClearance = CONTENT_CLEARANCE) {
  const insets = useSafeAreaInsets();
  return ACTION_MARGIN + ACTION_SIZE + extraClearance + insets.bottom;
}

export function FloatingCreateAction({
  icon: Icon = Plus,
  label,
  onPress,
  testID,
}: FloatingCreateActionProps) {
  const { colors } = useAppTheme();
  const insets = useSafeAreaInsets();

  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="button"
      onPress={onPress}
      style={({ pressed }) => [
        styles.action,
        {
          backgroundColor: colors.accent,
          bottom: ACTION_MARGIN + insets.bottom,
          right: ACTION_MARGIN + insets.right,
          shadowColor: colors.text,
        },
        pressed && styles.pressed,
      ]}
      testID={testID}
    >
      <Icon color={colors.accentText} size={28} strokeWidth={2.5} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  action: {
    alignItems: 'center',
    borderRadius: ACTION_SIZE / 2,
    elevation: 7,
    height: ACTION_SIZE,
    justifyContent: 'center',
    position: 'absolute',
    shadowOffset: { height: 3, width: 0 },
    shadowOpacity: 0.24,
    shadowRadius: 5,
    width: ACTION_SIZE,
  },
  pressed: { opacity: 0.72, transform: [{ scale: 0.96 }] },
});
