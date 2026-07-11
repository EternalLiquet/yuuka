import { StyleSheet, View } from 'react-native';

import { AppText } from '@/components/app-text';
import { Screen } from '@/components/screen';
import { useAppTheme } from '@/theme/use-app-theme';

type PlaceholderScreenProps = {
  title: string;
};

export function PlaceholderScreen({ title }: PlaceholderScreenProps) {
  const { colors } = useAppTheme();

  return (
    <Screen contentContainerStyle={styles.screen}>
      <View style={[styles.panel, { backgroundColor: colors.surface, borderColor: colors.border }]}>
        <AppText variant="title">{title}</AppText>
        <AppText style={{ color: colors.muted }}>Specification pending.</AppText>
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  screen: {
    padding: 16,
  },
  panel: {
    borderRadius: 8,
    borderWidth: 1,
    gap: 8,
    padding: 18,
  },
});
