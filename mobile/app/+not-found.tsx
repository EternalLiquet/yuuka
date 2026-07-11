import { Link, Stack } from 'expo-router';
import { StyleSheet } from 'react-native';

import { AppText } from '@/components/app-text';
import { Screen } from '@/components/screen';
import { useAppTheme } from '@/theme/use-app-theme';

export default function NotFoundScreen() {
  const { colors } = useAppTheme();

  return (
    <>
      <Stack.Screen options={{ title: 'Not Found' }} />
      <Screen contentContainerStyle={styles.container}>
        <AppText variant="title">Not found</AppText>

        <Link href="/" style={styles.link}>
          <AppText style={[styles.linkText, { color: colors.accent }]}>Return to Yuuka</AppText>
        </Link>
      </Screen>
    </>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 20,
  },
  link: {
    marginTop: 15,
    paddingVertical: 15,
  },
  linkText: {
    fontSize: 14,
  },
});
