import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { Stack, ThemeProvider } from 'expo-router';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { StatusBar } from 'expo-status-bar';
import 'react-native-reanimated';
import { StyleSheet } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { AuthProvider } from '@/auth/auth-provider';
import { Screen } from '@/components/screen';
import { QueryProvider } from '@/providers/query-provider';
import { SettingsProvider, useSettings } from '@/settings/settings-provider';
import { navigationThemes } from '@/theme/navigation-theme';
import { useAppTheme } from '@/theme/use-app-theme';

export { ErrorBoundary } from 'expo-router';

export const unstable_settings = {
  initialRouteName: 'index',
};

export default function RootLayout() {
  usePreventScreenCapture();

  return (
    <GestureHandlerRootView style={styles.root} testID="gesture-handler-root">
      <SafeAreaProvider>
        <SettingsProvider>
          <RootProviders />
        </SettingsProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

function RootProviders() {
  const { isLoading } = useSettings();
  if (isLoading) {
    return <Screen />;
  }
  return (
    <QueryProvider>
      <AuthProvider>
        <RootNavigation />
      </AuthProvider>
    </QueryProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
});

function RootNavigation() {
  const { colorMode, colors } = useAppTheme();
  return (
    <ThemeProvider value={navigationThemes[colorMode]}>
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      />
      <StatusBar style={colorMode === 'dark' ? 'light' : 'dark'} />
    </ThemeProvider>
  );
}
